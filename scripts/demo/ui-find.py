"""uiautomator 화면 덤프(xml)에서 글자가 정확히 같은 요소를 찾아 가운데 좌표 'x y'를 출력한다. 없으면 아무것도 출력하지 않는다.

    python3 scripts/demo/ui-find.py <ui.xml> <글자>
"""
import re
import sys

xml = open(sys.argv[1], encoding="utf-8", errors="ignore").read()
for node in re.findall(r"<node [^>]*>", xml):
    text = re.search(r' text="([^"]*)"', node)
    bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
    if text and bounds and text.group(1).strip() == sys.argv[2]:
        x1, y1, x2, y2 = map(int, bounds.groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
