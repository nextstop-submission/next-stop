"""uiautomator 화면 덤프(xml)를 읽는다(실측 스크립트용).

    python3 scripts/probe/ui-nodes.py list <ui.xml>                    # 글자 있는 요소: text|desc|id|class|clickable|bounds
    python3 scripts/probe/ui-nodes.py tap-contains <ui.xml> <글자> [n]  # 글자를 포함하는 n번째(0부터) 요소의 가운데 'x y'
    python3 scripts/probe/ui-nodes.py tap-any <ui.xml> <글자1,글자2,…>  # 정확히 같은 글자 중 처음 찾은 것의 가운데 'x y'
못 찾으면 아무것도 출력하지 않는다.
"""
import re
import sys


def nodes(path):
    xml = open(path, encoding="utf-8", errors="ignore").read()
    for raw in re.findall(r"<node [^>]*>", xml):
        a = dict(re.findall(r' ([\w-]+)="([^"]*)"', raw))
        b = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", a.get("bounds", ""))
        if not b:
            continue
        x1, y1, x2, y2 = map(int, b.groups())
        a["cx"], a["cy"] = (x1 + x2) // 2, (y1 + y2) // 2
        yield a


def label(a):
    return (a.get("text") or a.get("content-desc") or "").strip()


cmd, path = sys.argv[1], sys.argv[2]
if cmd == "list":
    for a in nodes(path):
        if label(a):
            print("|".join([a.get("text", ""), a.get("content-desc", ""), a.get("resource-id", ""), a.get("class", ""),
                            a.get("clickable", ""), a.get("bounds", ""), a.get("package", "")]))
elif cmd == "tap-contains":
    want, nth = sys.argv[3], int(sys.argv[4]) if len(sys.argv) > 4 else 0
    hits = [a for a in nodes(path) if want in label(a) and "EditText" not in a.get("class", "")]
    if len(hits) > nth:
        print(hits[nth]["cx"], hits[nth]["cy"])
elif cmd == "tap-any":
    for want in sys.argv[3].split(","):
        hit = next((a for a in nodes(path) if label(a) == want), None)
        if hit:
            print(hit["cx"], hit["cy"], want)
            break
