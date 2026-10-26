#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build_release_apk.py —— 水印盒 release 打包脚本

背景：
    AGP 9.0.0 在 arm64/proot 环境下，`packageRelease` 任务会把
    AndroidManifest.xml 与 resources.arsc 丢出 APK，导致产物无法安装。
    （中间产物 linked-resources-binary-format-release.ap_ 是完好的，
     只是打包步骤没有把它并进去。）

本脚本绕过 AGP 的打包步骤，手动完成组装：
    1. 从 linked_resources_binary_format 解出 manifest / res / arsc
    2. 从 mergeDexRelease 取混淆后的 dex
    3. 组装 APK：resources.arsc 必须 Stored(不压缩) 且 4 字节对齐（Android R+ 要求）
    4. 用 release 密钥签名（v1+v2+v3）

用法：
    python3 tools/build_release_apk.py
产物：
    app/build/outputs/apk/release/app-release.apk
"""

import os
import struct
import subprocess
import sys
import zipfile
import shutil
import tempfile

# ---------------- 配置 ----------------
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BUILD = os.path.join(ROOT, "app", "build")
INTER = os.path.join(BUILD, "intermediates")

AP_ = os.path.join(
    INTER, "linked_resources_binary_format", "release",
    "processReleaseResources", "linked-resources-binary-format-release.ap_",
)
DEX_DIR = os.path.join(INTER, "dex", "release", "mergeDexRelease")
OUT_APK = os.path.join(BUILD, "outputs", "apk", "release", "app-release.apk")

# AGP 的 packageRelease 会产出「残缺但 dex 正确」的 APK。
# R8 混淆后的 dex 就在里面（classes.dex 等）。
# 我们先把它读出来，再用正确的资源重新打包。
STUB_APK = os.path.join(BUILD, "outputs", "apk", "release", "app-release.apk")

# 备用：如果上面那个包不在了，从这些目录找 dex
DEX_FALLBACK_DIRS = [
    os.path.join(INTER, "dex", "release", "mergeDexRelease"),
    os.path.join(INTER, "project_dex_archive", "release"),
]
KEYSTORE_PROPERTIES = os.path.join(ROOT, "keystore.properties")


def _load_signing_config():
    """从 keystore.properties 读取签名配置。

    该文件已被 .gitignore 排除，密码不会进入版本库。
    若文件不存在，则回退到环境变量，方便 CI 使用。
    """
    props = {}
    if os.path.isfile(KEYSTORE_PROPERTIES):
        with open(KEYSTORE_PROPERTIES, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                props[k.strip()] = v.strip()

    store_file = props.get("storeFile") or os.environ.get("WMB_STORE_FILE")
    ks_pass = props.get("storePassword") or os.environ.get("WMB_STORE_PASSWORD")
    key_alias = props.get("keyAlias") or os.environ.get("WMB_KEY_ALIAS")
    key_pass = props.get("keyPassword") or os.environ.get("WMB_KEY_PASSWORD")

    if not all([store_file, ks_pass, key_alias, key_pass]):
        sys.exit(
            "缺少签名配置：请在项目根目录创建 keystore.properties\n"
            "（可参考 README 的「Release 签名」一节），\n"
            "或通过 WMB_STORE_FILE / WMB_STORE_PASSWORD / "
            "WMB_KEY_ALIAS / WMB_KEY_PASSWORD 环境变量提供。"
        )

    ks_path = store_file if os.path.isabs(store_file) else os.path.join(ROOT, store_file)
    return ks_path, ks_pass, key_alias, key_pass


KEYSTORE, KS_PASS, KEY_ALIAS, KEY_PASS = _load_signing_config()


def _find_apksigner():
    """按常见位置查找 apksigner，找不到就回退到 PATH。

    注意：apksigner 不总是无后缀的可执行文件 —— Windows 的 SDK 里叫
    apksigner.bat，只按 "apksigner" 去 glob 会**永远命中不了**，最后落到
    sign_apk() 的「找不到 apksigner」。所以 glob 放宽为 apksigner*，
    且同一 build-tools 目录内优先 .bat。
    """
    import glob

    def _lookup(base):
        # 先按 build-tools 版本号倒序（新版优先），
        # 再稳定排序让同目录内的 .bat 排在无后缀版本之前。
        paths = sorted(
            glob.glob(os.path.join(base, "build-tools", "*", "apksigner*")), reverse=True
        )
        return sorted(paths, key=lambda p: 0 if p.endswith(".bat") else 1)

    candidates = []
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        candidates += _lookup(sdk)
    # 常见 SDK 安装位置的回退探测
    for base in (os.path.expanduser("~/Android"), "/opt/android-sdk", "/usr/lib/android-sdk"):
        candidates += _lookup(base)
    for c in candidates:
        if os.path.isfile(c):
            return c
    # 回退：依赖 PATH。
    #
    # ⚠️ 必须用 `shutil.which` 解析，不能直接把裸名字 `"apksigner"` 交给调用方 ——
    #    调用方 `sign_apk()` 里的判断是 `os.path.isfile(APKSIGNER)`，
    #    而裸名字**永远不是**一个存在的文件路径，于是这条 PATH 回退
    #    必然报「找不到 apksigner」（Linux 上同样如此，不是 Windows 专属）。
    #    Windows 上真正的文件名是 `apksigner.bat`，which 会一并解决。
    return shutil.which("apksigner") or "apksigner"


APKSIGNER = os.environ.get("APKSIGNER") or _find_apksigner()


def log(msg):
    print(f"[build] {msg}", flush=True)


# ---------------- 1. 收集素材 ----------------
def collect_inputs():
    """返回 (资源包路径, stub APK 路径)。"""
    if not os.path.isfile(AP_):
        sys.exit(f"找不到资源包：{AP_}\n请先执行 ./gradlew assembleRelease")

    # AGP 打出来的包（虽然缺 manifest/arsc，但 dex / lib / assets 是对的）
    if not os.path.isfile(STUB_APK):
        sys.exit(
            f"找不到 AGP 产出的 APK：{STUB_APK}\n"
            "请先执行 ./gradlew assembleRelease，再运行本脚本"
        )

    with zipfile.ZipFile(STUB_APK) as z:
        dex_names = sorted(n for n in z.namelist() if n.endswith(".dex"))

    if not dex_names:
        sys.exit(f"{STUB_APK} 中没有 dex")

    log(f"资源包：{os.path.basename(AP_)} ({os.path.getsize(AP_)} bytes)")
    log(f"stub  ：{os.path.basename(STUB_APK)} ({os.path.getsize(STUB_APK)} bytes)")
    log(f"dex   ：{', '.join(dex_names)}")
    return dex_names


# ---------------- 2. 读取 stub 里的文件 ----------------
def read_stub_files():
    """
    从 AGP 产出的 APK 中读出所有「正确」的内容：
    dex、lib/、assets/、以及各种 META-INF 版本标记。
    返回 [(arcname, bytes)]。
    """
    skip_prefix = ("META-INF/",)   # 签名相关全部丢弃（稍后重新签）
    keep_prefix = ("lib/", "assets/")

    out = []
    with zipfile.ZipFile(STUB_APK) as z:
        for name in z.namelist():
            if name.endswith("/"):
                continue
            if name.startswith(skip_prefix):
                continue
            if name.endswith(".dex") or name.startswith(keep_prefix):
                out.append((name, z.read(name)))
    return out


# ---------------- 2. 组装 zip ----------------
# 需要【不压缩】存储的文件：
#   - resources.arsc : Android R+ 强制要求 Stored + 4 字节对齐
#   - *.so           : extractNativeLibs 要求原生库保持未压缩，否则安装失败
# 其余文件（含 dex）正常压缩，以控制体积。
STORED_NAMES = ("resources.arsc",)
STORED_SUFFIX = (".so",)


def should_store(name):
    base = os.path.basename(name)
    if base in STORED_NAMES:
        return True
    return name.endswith(STORED_SUFFIX)


def build_apk(out_path):
    tmp = tempfile.mkdtemp(prefix="wmb_pack_")
    try:
        # --- 解资源包 ---
        res_dir = os.path.join(tmp, "res")
        os.makedirs(res_dir, exist_ok=True)
        with zipfile.ZipFile(AP_, "r") as z:
            z.extractall(res_dir)
        log("已解出资源包内容")

        # --- 收集条目 ---
        entries = []  # (arcname, bytes, compress)
        for dirpath, _, filenames in os.walk(res_dir):
            for fn in filenames:
                full = os.path.join(dirpath, fn)
                arc = os.path.relpath(full, res_dir).replace(os.sep, "/")
                with open(full, "rb") as f:
                    entries.append((arc, f.read(), not should_store(arc)))

        # --- 从 stub APK 取 dex / lib / assets ---
        stub_items = read_stub_files()
        for arc, data in stub_items:
            entries.append((arc, data, not should_store(arc)))
        log(f"从 stub 取到 {len(stub_items)} 个文件（dex/lib/assets）")

        # 排序：resources.arsc 靠最前，其余按名
        entries.sort(key=lambda e: (0 if e[0] == "resources.arsc" else 1, e[0]))

        os.makedirs(os.path.dirname(out_path), exist_ok=True)
        if os.path.exists(out_path):
            os.remove(out_path)

        with zipfile.ZipFile(out_path, "w") as z:
            for arc, data, compress in entries:
                zi = zipfile.ZipInfo(arc, date_time=(1980, 1, 1, 0, 0, 0))
                zi.compress_type = (
                    zipfile.ZIP_DEFLATED if compress else zipfile.ZIP_STORED
                )
                zi.external_attr = 0o644 << 16
                z.writestr(zi, data)

        log(f"初步组装完成：{os.path.getsize(out_path)} bytes")
        return out_path
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


# ---------------- 3. 4 字节对齐 ----------------
def align_arsc_strict(path):
    """
    严格实现：重新计算所有 central directory 记录。
    zipfile 生成的结构中，各条目在 LFH 区的顺序与 CD 区一致。
    """
    with open(path, "rb") as f:
        data = bytearray(f.read())

    LFH = b"PK\x03\x04"
    CDH = b"PK\x01\x02"

    # 1) 遍历 local headers，记录每个条目的文件名与数据区偏移
    entries = []
    p = 0
    while p + 4 <= len(data) and data[p:p + 4] == LFH:
        nlen = struct.unpack_from("<H", data, p + 26)[0]
        elen = struct.unpack_from("<H", data, p + 28)[0]
        csize = struct.unpack_from("<I", data, p + 18)[0]
        name = data[p + 30:p + 30 + nlen].decode("utf-8", "replace")
        entries.append({"lfh": p, "nlen": nlen, "elen": elen, "csize": csize, "name": name})
        p = p + 30 + nlen + elen + csize

    if not entries:
        sys.exit("APK 中没有找到任何 local file header")

    # 2) 找到 arsc，计算需要的填充
    target = None
    for e in entries:
        if e["name"] == "resources.arsc":
            target = e
            break
    if target is None:
        sys.exit("未找到 resources.arsc")

    body_off = target["lfh"] + 30 + target["nlen"] + target["elen"]
    pad = (4 - (body_off % 4)) % 4
    log(f"[strict] arsc 偏移={body_off} mod4={body_off % 4} 填充={pad}")

    if pad:
        ins = target["lfh"] + 30 + target["nlen"] + target["elen"]
        data[ins:ins] = b"\x00" * pad
        struct.pack_into("<H", data, target["lfh"] + 28, target["elen"] + pad)

    # 3) 重新扫描（因为插入了字节），重建 central directory
    entries2 = []
    p = 0
    while p + 4 <= len(data) and data[p:p + 4] == LFH:
        nlen = struct.unpack_from("<H", data, p + 26)[0]
        elen = struct.unpack_from("<H", data, p + 28)[0]
        csize = struct.unpack_from("<I", data, p + 18)[0]
        name = data[p + 30:p + 30 + nlen].decode("utf-8", "replace")
        entries2.append((p, name))
        p = p + 30 + nlen + elen + csize

    cd_off = data.find(CDH)
    if cd_off < 0:
        sys.exit("未找到 central directory")

    # 依次修正 CD 中的 local header offset
    q = cd_off
    idx = 0
    while q + 4 <= len(data) and data[q:q + 4] == CDH:
        cn = struct.unpack_from("<H", data, q + 28)[0]
        ce = struct.unpack_from("<H", data, q + 30)[0]
        cname = data[q + 46:q + 46 + cn].decode("utf-8", "replace")

        # 找到该名字最新的 LFH 偏移
        new_off = None
        for off, nm in entries2:
            if nm == cname:
                new_off = off
                break
        if new_off is not None:
            struct.pack_into("<I", data, q + 42, new_off)
        if cname == "resources.arsc":
            struct.pack_into("<H", data, q + 30, ce + pad)

        q = q + 46 + cn + ce
        idx += 1

    with open(path, "wb") as f:
        f.write(data)
    log(f"[strict] 已处理 {idx} 条中央目录记录")


# ---------------- 4. 签名 ----------------
def sign_apk(path):
    # 既接受具体文件路径（`APKSIGNER` 环境变量 / SDK build-tools 里找到的），
    # 也接受 PATH 上的命令名（`shutil.which` 解析）。
    # 只判 `os.path.isfile` 会把 PATH 回退判死 —— 见 `_find_apksigner()` 末尾注释。
    if not (os.path.isfile(APKSIGNER) or shutil.which(APKSIGNER)):
        sys.exit(f"找不到 apksigner：{APKSIGNER}")

    signed = path + ".signed"
    if os.path.exists(signed):
        os.remove(signed)

    cmd = [
        APKSIGNER, "sign",
        "--ks", KEYSTORE,
        "--ks-pass", f"pass:{KS_PASS}",
        "--key-pass", f"pass:{KEY_PASS}",
        "--ks-key-alias", KEY_ALIAS,
        "--v1-signing-enabled", "true",
        "--v2-signing-enabled", "true",
        "--v3-signing-enabled", "true",
        "--out", signed,
        path,
    ]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"签名失败：\n{r.stdout}\n{r.stderr}")

    os.replace(signed, path)
    log("签名完成（v1+v2+v3）")


# ---------------- 主流程 ----------------
def main():
    # 关键：脚本要从 AGP 产出的包里读 dex，但最终又要写回同一路径。
    # 所以先把 stub 备份到临时位置，避免读到一半被自己覆盖。
    import shutil as _sh
    stub_backup = os.path.join(BUILD, "outputs", "apk", "release", ".stub_backup.apk")
    if not os.path.isfile(STUB_APK):
        sys.exit(
            f"找不到 AGP 产出的 APK：{STUB_APK}\n"
            "请先执行 ./gradlew assembleRelease"
        )
    _sh.copyfile(STUB_APK, stub_backup)

    globals()["STUB_APK"] = stub_backup

    try:
        collect_inputs()
        build_apk(OUT_APK)
        align_arsc_strict(OUT_APK)
        sign_apk(OUT_APK)
    finally:
        if os.path.exists(stub_backup):
            os.remove(stub_backup)

    size = os.path.getsize(OUT_APK)
    log(f"完成 ✅  {OUT_APK}  ({size} bytes / {size/1024/1024:.2f} MB)")

    # 自检
    log("自检：")
    with zipfile.ZipFile(OUT_APK) as z:
        names = z.namelist()
        for key in ("AndroidManifest.xml", "resources.arsc", "classes.dex"):
            ok = key in names
            log(f"  {'✅' if ok else '❌'} {key}")
        info = z.getinfo("resources.arsc")
        log(f"  resources.arsc 压缩方式：{'Stored ✅' if info.compress_type == 0 else 'Deflated ❌'}")
        log(f"  条目总数：{len(names)}")


if __name__ == "__main__":
    main()
