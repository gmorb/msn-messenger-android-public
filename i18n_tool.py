#!/usr/bin/env python3
"""Wrap UI text sinks in I18n.tr(...) and extract English strings/patterns.
Usage: i18n_tool.py dry|apply"""
import re, sys, os, json

SRC = '/a0/usr/workdir/msn-fresh-android/app/src/main/java/com/msnmessenger/'
DOT_SINKS = {'setText': 0, 'setHint': 0, 'setTitle': 0, 'setMessage': 0, 'setPositiveButton': 0,
             'setNegativeButton': 0, 'setNeutralButton': 0, 'setItems': 0, 'setContentTitle': 0,
             'setContentText': 0, 'setTicker': 0, 'setBigContentTitle': 0, 'bigText': 0, 'setSummaryText': 0}
SKIP_FILES = {'I18n.java'}


def scan_str(s, i):
    """i at opening quote; return index after closing quote"""
    q = s[i]; i += 1
    while s[i] != q:
        i += 2 if s[i] == '\\' else 1
    return i + 1


def split_args(s, op):
    """op = index of '('. returns (args[(a,b)], close_index)"""
    depth = 0; i = op; args = []; start = op + 1
    while True:
        c = s[i]
        if c in '"\'':
            i = scan_str(s, i); continue
        if c in '([{':
            depth += 1
        elif c in ')]}':
            depth -= 1
            if depth == 0:
                args.append((start, i)); return args, i
        elif c == ',' and depth == 1:
            args.append((start, i)); start = i + 1
        i += 1


def jdecode(lit):
    body = lit[1:-1]
    out = []; i = 0
    while i < len(body):
        c = body[i]
        if c == '\\':
            n = body[i + 1]
            if n == 'u':
                out.append(chr(int(body[i + 2:i + 6], 16))); i += 6; continue
            out.append({'n': '\n', 't': '\t', '"': '"', "'": "'", '\\': '\\'}.get(n, n)); i += 2; continue
        out.append(c); i += 1
    return ''.join(out)


def top_split(expr, sep):
    parts = []; depth = 0; i = 0; start = 0
    while i < len(expr):
        c = expr[i]
        if c in '"\'':
            i = scan_str(expr, i); continue
        if c in '([{': depth += 1
        elif c in ')]}': depth -= 1
        elif depth == 0 and expr.startswith(sep, i):
            parts.append(expr[start:i]); start = i + len(sep); i += len(sep); continue
        i += 1
    parts.append(expr[start:])
    return [p.strip() for p in parts]


def ternary_branches(expr):
    """top-level a ? b : c -> [b, c] (recursive on c)"""
    q = top_split(expr, '?')
    if len(q) < 2: return None
    rest = '?'.join(q[1:])
    bc = top_split(rest, ':')
    if len(bc) < 2: return None
    return [bc[0], ':'.join(bc[1:])]


LIT_RE = re.compile(r'"(?:[^"\\]|\\.)*"')


def patterns_of(expr, out, exact):
    expr = expr.strip()
    while expr.startswith('(') and expr.endswith(')') and split_args(expr, 0)[1] == len(expr) - 1:
        expr = expr[1:-1].strip()
    tb = ternary_branches(expr)
    if tb:
        for b in tb: patterns_of(b, out, exact)
        return
    terms = top_split(expr, '+')
    pat = ''; n = 0; lits = 0
    for t in terms:
        if LIT_RE.fullmatch(t):
            pat += jdecode(t).replace('%', '%%'); lits += 1
        else:
            n += 1; pat += '%%%d$s' % n
            for lit in LIT_RE.findall(t): exact.add(jdecode(lit))
            # nested ternaries inside a term
            inner = t[1:-1] if t.startswith('(') and t.endswith(')') else None
            if inner and ternary_branches(inner):
                patterns_of(inner, out, exact)
    if lits and re.search('[A-Za-z]{2,}', re.sub(r'%\d\$s', '', pat)):
        out.add(pat)


def should_wrap(arg):
    a = arg.strip()
    if not a or a.startswith('I18n.') or a.startswith('R.') or a == 'null' or re.fullmatch(r'-?\d+', a):
        return False
    if LIT_RE.search(a): return True
    return bool(re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*(\[[^\]]+\])?', a))


def process(path, apply):
    s = open(path).read()
    pats, exact = set(), set()
    edits = []
    for m in re.finditer(r'\.(%s)\(' % '|'.join(DOT_SINKS), s):
        op = m.end() - 1
        args, _ = split_args(s, op)
        a, b = args[DOT_SINKS[m.group(1)]]
        edits.append((a, b, m.group(1)))
    for m in re.finditer(r'(?<![\w.])(addLine|msnAlert)\(', s):
        args, _ = split_args(s, m.end() - 1)
        k = 0 if m.group(1) == 'addLine' else 1
        if len(args) > k: edits.append((args[k][0], args[k][1], m.group(1)))
    for m in re.finditer(r'Toast\.makeText\(', s):
        args, _ = split_args(s, m.end() - 1)
        if len(args) >= 2: edits.append((args[1][0], args[1][1], 'Toast'))
    wraps = 0
    for a, b, kind in sorted(edits, reverse=True):
        arg = s[a:b]
        if not should_wrap(arg): continue
        patterns_of(arg, pats, exact)
        lead = len(arg) - len(arg.lstrip())
        s = s[:a + lead] + 'I18n.tr(' + arg.strip() + ')' + s[b:]
        wraps += 1
    if apply: open(path, 'w').write(s)
    return wraps, pats, exact


if __name__ == '__main__':
    apply = len(sys.argv) > 1 and sys.argv[1] == 'apply'
    allp, alle = set(), set()
    for f in sorted(os.listdir(SRC)):
        if not f.endswith('.java') or f in SKIP_FILES: continue
        w, p, e = process(SRC + f, apply)
        print('%-26s wraps=%d' % (f, w))
        allp |= p; alle |= e
    alle = {x for x in alle if re.search('[A-Za-z]{2,}', x)}
    json.dump({'patterns': sorted(allp), 'exact': sorted(alle - allp)}, open('/tmp/i18n_found.json', 'w'), indent=1, ensure_ascii=False)
    print('patterns', len(allp), 'exact-args', len(alle - allp))
