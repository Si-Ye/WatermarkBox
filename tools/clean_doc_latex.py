#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""清理项目文档里误用的「对话框美化语法」。

## 为什么需要这个脚本

AI 在**对话框里**回答时，会用 LaTeX 做卡片、彩色框、高亮（界面能渲染，
效果不错）。但这个习惯有个副作用：同一套写法被写进仓库的 `.md` 文档后，
在 GitHub、编辑器、终端里**只会显示成一堆原始命令**，例如：

    $$\\fcolorbox{red}{#FFEBEE}{\\textcolor{red}{\\textbf{\\text{不许推送}}}}}$$

读者看到的是这串字符本身，不是高亮 —— 既难看，又误导。

**这不是语法错误，是「工具用错了场合」。** 所以专门写个脚本统一清理，
而不是让每个会话手改（手改 15 处、还得逐层剥括号，极易出错）。

## 用法

    python3 tools/clean_doc_latex.py --check  docs/*.md   # 只报告，不改动
    python3 tools/clean_doc_latex.py --fix    docs/*.md   # 就地转换

## 转换规则

    $$\\fcolorbox{red}{#FFEBEE}{\\textcolor{red}{\\textbf{\\text{不许推送}}}}}$$
    ↓
    > ⚠️ **不许推送**

- 行内样式：`\\textbf{x}` → `**x**`，`\\texttt{x}` → `` `x` ``，`\\textit{x}` → `*x*`
- 纯装饰（颜色 / 边框 / 字号 / 缩放 / 旋转）：去掉外壳，**保留内容**
- 公式块 → 引用块（保留「这是重点」的视觉强调）

## 安全性

- 只处理**确实含装饰命令**的 `$...$` / `$$...$$` 片段，普通正文和代码块不动
  （正常数学公式如 `$O(n \\log n)$`、`$\\frac{a}{b}$` 必须原样保留）
- 转换是**幂等**的：重复跑不会继续变化（`--check` 可验证）
- 转换后如果残留了 LaTeX 反斜杠命令，脚本会**告警**，提醒人工看一眼

## 回归测试

`clean_doc_latex.sample.md`（输入）和 `clean_doc_latex.expected.md`（期望输出）
是一对配好的样例。**改动本脚本后请跑一遍**：

    cp tools/clean_doc_latex.sample.md /tmp/regress.md
    python3 tools/clean_doc_latex.py --fix /tmp/regress.md
    diff /tmp/regress.md tools/clean_doc_latex.expected.md   # 应无输出

样例特意覆盖了 5 类踩过的坑：多参数命令、嵌套加粗、行内加粗、
`>$$...$$` 的重复引用符、以及**正常数学公式必须原样保留**。
"""

import os
import re
import sys

# ── 命令语义 ──
#
# ⚠️ 最初这里是一张「正则替换表」，反复从内到外剥。
# 实测发现它处理不了 `\textcolor{A}{B}` 这类**多参数命令**：
# `\textcolor{A}` 被删掉后只剩 `{B}`，输出变成 `{**内容**}`（多出花括号）。
# 根因是正则分不清「哪对花括号是参数外壳、哪对是内容」。
# 所以改成下面的**递归下降解析**：显式读命令名 → 按平衡括号读参数组 → 按语义处理。

# 行内样式：包裹内容
_STYLE = {
    'textbf': ('**', '**'),
    'textit': ('*', '*'),
    'emph': ('*', '*'),
    'texttt': ('`', '`'),
    'underline': ('<u>', '</u>'),
}

# 透明命令：直接展开内容
_PLAIN = {'text', 'mathrm', 'mbox', 'textrm', 'textsf', 'operatorname'}

# 装饰命令：丢掉前置「设置参数」，保留**最后一个**（那才是内容）
_DECOR = {
    'fcolorbox': 2,   # \fcolorbox{边框色}{背景色}{内容}
    'textcolor': 1,   # \textcolor{颜色}{内容}
    'colorbox': 1,    # \colorbox{背景色}{内容}
    'scalebox': 1,    # \scalebox{倍数}{内容}
    'rotatebox': 1,   # \rotatebox{角度}{内容}
}

# 装饰命令：参数即内容（或本就无参数）
_DECOR_BARE = {'shadowbox', 'doublebox', 'ovalbox', 'Ovalbox',
               'fbox', 'boxed', 'reflectbox'}

# 纯字号 / 排版噪声：直接丢
_SIZE = {'tiny', 'small', 'normalsize', 'large', 'Large', 'LARGE', 'huge', 'Huge'}
_DROP = {'substack', 'quad', 'qquad'}

# 公式定界符：块级优先，行内次之
_BLOCK = re.compile(r'\$\$(.+?)\$\$', re.S)
_INLINE = re.compile(r'(?<!\$)\$(?!\$)([^$\n]+?)\$(?!\$)')

# 判断片段是否「含装饰命令」—— 不含就不动，避免误伤正常数学公式
# （例如 `$O(n \log n)$` 必须原样保留）
_SUSPECT = re.compile(
    r'\\(?:' + '|'.join(sorted(
        set(_STYLE) | _PLAIN | set(_DECOR) | _DECOR_BARE | _SIZE
        | {'fcolorbox', 'textcolor', 'colorbox', 'shadowbox', 'ovalbox'}
    )) + r')\b'
)


def _read_group(s, i):
    """s[i] 必须是 '{'。按**平衡括号**读取，返回 (组内内容, 下一位置)。"""
    if i >= len(s) or s[i] != '{':
        return '', i
    depth = 0
    j = i
    while j < len(s):
        if s[j] == '{':
            depth += 1
        elif s[j] == '}':
            depth -= 1
            if depth == 0:
                return s[i + 1:j], j + 1
        j += 1
    return s[i + 1:], len(s)   # 未闭合 → 容错


def render(s):
    """递归解析 LaTeX 装饰串，返回纯 Markdown 文本。"""
    out = []
    i = 0
    n = len(s)
    while i < n:
        ch = s[i]

        if ch != '\\':
            out.append(ch)
            i += 1
            continue

        # 读命令名
        j = i + 1
        while j < n and s[j].isalpha():
            j += 1
        cmd = s[i + 1:j]

        # 读全部参数组
        args = []
        k = j
        while k < n and s[k] == '{':
            grp, k = _read_group(s, k)
            args.append(grp)
        i = k

        if cmd in _SIZE or cmd in _DROP:
            continue

        if cmd in _STYLE:
            pre, post = _STYLE[cmd]
            out.append(pre + render(args[0] if args else '') + post)
        elif cmd in _PLAIN:
            out.append(render(args[0] if args else ''))
        elif cmd in _DECOR:
            if args:
                out.append(render(args[-1]))      # 保留最后一个（内容）
        elif cmd in _DECOR_BARE:
            if args:
                out.append(render(args[0]))
        else:
            # 未知命令：保守处理 —— 丢命令名，展开参数
            for a in args:
                out.append(render(a))

    return ''.join(out)


def _cleanup(text):
    """收尾：折叠空白、扁平化嵌套加粗、修掉遗留花括号。"""
    text = re.sub(r'\s+', ' ', text).strip()
    # 空的加粗 / 斜体标记（`****`、`* *`）
    #
    # ⚠️ 斜体这条必须用 `\s+` 而不是 `\s*`：原写法 `\*\s*\*` 会匹配
    # 相邻的两个 `*`，把真正的 `**加粗**` 吃掉 —— 实测踩到。
    for _ in range(3):
        text = re.sub(r'\*\*\s*\*\*', '', text)
        text = re.sub(r'(?<!\*)\*\s+\*(?!\*)', '', text)
    # 残留的裸花括号（装饰外壳的漏网之鱼）
    text = text.replace('{', '').replace('}', '')
    # Markdown 不支持嵌套加粗：`**A**绝不**B**` 会渲染错乱。
    # 整体已被 `**` 包裹时，把内部残留的 `**` 拍平。
    #
    # ⚠️ 这里曾经还有两条「去掉首尾强调符」的规则（`^\*+\s*` / `\s*\*+$`），
    # 结果把合法的 `**重点**` 剥成 `重点`（行内加粗整片丢失）—— 实测踩到，已删除。
    # 首尾强调符的处理交给 _to_block 的 startswith/endswith 判断。
    if text.startswith('**') and text.endswith('**') and len(text) > 4:
        text = '**%s**' % text[2:-2].replace('**', '')
    return text.strip()


def _to_block(body):
    """公式体 → 引用块。"""
    text = _cleanup(render(body))
    if not text:
        return ''
    # 已经整体加粗的就别再套一层
    if text.startswith('**') and text.endswith('**'):
        return '> ⚠️ %s' % text
    return '> ⚠️ **%s**' % text


def _to_inline(body):
    """行内公式 → 普通文本（框类强调 → 加粗）。"""
    text = _cleanup(render(body))
    if not text:
        return ''
    if re.search(r'\\(?:fcolorbox|colorbox|shadowbox|ovalbox)', body):
        text = '**%s**' % re.sub(r'^\*+|\*+$', '', text).strip()
    return text


def convert(text: str):
    """整篇转换，返回 (新内容, 改动数, 残留告警)。"""
    hits = [0]
    leftovers = []

    def _check(out):
        if re.search(r'\\[a-zA-Z]{2,}', out):
            leftovers.append(out[:80])

    def _block_sub(m):
        if not _SUSPECT.search(m.group(1)):
            return m.group(0)
        out = _to_block(m.group(1))
        _check(out)
        hits[0] += 1
        return out

    def _inline_sub(m):
        if not _SUSPECT.search(m.group(1)):
            return m.group(0)
        out = _to_inline(m.group(1))
        _check(out)
        hits[0] += 1
        return out

    text = _BLOCK.sub(_block_sub, text)
    text = _INLINE.sub(_inline_sub, text)
    # 原文若已有 `>` 前缀（如 `>$$...$$`），替换后会变成 `>>`；压回一个。
    # 只处理「行首连续且**无空格**的 >」，避免误伤正常的嵌套引用（`> > `）。
    text = re.sub(r'^>>+', '>', text, flags=re.M)
    return text, hits[0], leftovers


def main(argv):
    args = argv[1:]
    if not args or args[0] not in ('--check', '--fix'):
        print(__doc__)
        return 2

    mode = args[0]
    files = args[1:]
    if not files:
        print('错误：请指定文件（例如 docs/*.md）', file=sys.stderr)
        return 2

    total_files = 0
    total_hits = 0
    all_leftovers = []

    for path in files:
        if not os.path.isfile(path):
            continue
        with open(path, 'r', encoding='utf-8') as f:
            original = f.read()

        new, hits, leftovers = convert(original)
        if hits == 0:
            continue

        total_files += 1
        total_hits += hits
        print('%-52s %2d 处' % (path, hits))
        for lo in leftovers:
            all_leftovers.append((path, lo))

        if mode == '--fix' and new != original:
            with open(path, 'w', encoding='utf-8') as f:
                f.write(new)

    print('')
    if total_files == 0:
        print('✅ 干净：没有需要转换的片段。')
    else:
        verb = '已转换' if mode == '--fix' else '待转换'
        print('%s %d 个文件、%d 处。' % (verb, total_files, total_hits))
        if mode == '--check':
            print('（用 --fix 执行转换）')

    if all_leftovers:
        print('')
        print('⚠️ 以下片段转换后仍有残留命令，请人工确认：')
        for path, lo in all_leftovers[:20]:
            print('   %s : %s' % (path, lo))
        return 1

    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
