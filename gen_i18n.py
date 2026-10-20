#!/usr/bin/env python3
"""Build Android per-language resources from i18n/<lang>.json.

Each JSON maps exact English text -> translation. Produces:
  res/values-<lang>/i18n.xml    (i18n_tr array, same order as i18n_keys.json)
  res/values-<lang>/strings.xml (layout strings, matched by their English value)
Missing keys fall back to English. Usage: gen_i18n.py [lang ...]
"""
import json, os, sys, re
import xml.etree.ElementTree as ET

P = '/a0/usr/workdir/msn-fresh-android/'
RES = P + 'app/src/main/res/'
KEYS = json.load(open(P + 'i18n_keys.json'))
NO_TRANSLATE = {'app_name'}


def esc(v):
    v = (v.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
          .replace('\\', '\\\\').replace("'", "\\'").replace('"', '\\"').replace('\n', '\\n'))
    if v[:1] in '@?':
        v = '\\' + v
    if v[:1] == ' ' or v[-1:] == ' ':
        v = '"' + v + '"'
    return v


def base_strings():
    """name -> English text from res/values/strings.xml"""
    out = {}
    for e in ET.parse(RES + 'values/strings.xml').getroot().findall('string'):
        t = e.text or ''
        if len(t) >= 2 and t[0] == '"' and t[-1] == '"':
            t = t[1:-1]
        t = t.replace("\\'", "'").replace('\\"', '"')
        out[e.get('name')] = t
    return out


def gen(lang):
    tr = json.load(open(P + 'i18n/%s.json' % lang, encoding='utf-8'))
    strs = base_strings()
    known = set(KEYS) | set(strs.values())
    missing = [k for k in KEYS if k not in tr]
    missing += [v for n, v in strs.items() if n not in NO_TRANSLATE and v not in tr]
    unknown = [k for k in tr if k not in known]
    # placeholder sanity: same %n$s set
    bad = [k for k, v in tr.items() if sorted(re.findall(r'%\d\$s', k)) != sorted(re.findall(r'%\d\$s', v))]
    d = RES + 'values-%s/' % lang
    os.makedirs(d, exist_ok=True)
    items = ''.join('        <item>%s</item>\n' % esc(tr.get(k, k)) for k in KEYS)
    open(d + 'i18n.xml', 'w', encoding='utf-8').write(
        '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <string-array name="i18n_tr">\n'
        + items + '    </string-array>\n</resources>\n')
    lines = ''.join('    <string name="%s">%s</string>\n' % (n, esc(tr[v]))
                    for n, v in strs.items() if n not in NO_TRANSLATE and v in tr)
    open(d + 'strings.xml', 'w', encoding='utf-8').write(
        '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n' + lines + '</resources>\n')
    print('%-4s keys=%d missing=%d unknown=%d placeholder_mismatch=%d' % (lang, len(tr), len(missing), len(unknown), len(bad)))
    for k in missing: print('   MISSING', repr(k))
    for k in unknown: print('   UNKNOWN', repr(k))
    for k in bad: print('   PLACEHOLDER', repr(k))


if __name__ == '__main__':
    langs = sys.argv[1:] or sorted(f[:-5] for f in os.listdir(P + 'i18n') if f.endswith('.json'))
    for l in langs:
        gen(l)
