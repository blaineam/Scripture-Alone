"""Turns a docs/manual/content/<locale>.html into the structured guide the apps draw natively.

The apps don't show the PDF: they lay the guide out themselves (SwiftUI on iPhone, iPad and Mac;
Compose on Android) so it reflows to the screen and follows the reader's text size. This module is
the one place that knows the HTML's shape; the apps only know the JSON below.

    {
      "schema": 1, "language": "ja", "title": "…",
      "cover": {"eyebrow", "title", "subtitle", "edition", "images": ["01-reader.png", …]},
      "chapters": [{"title", "summary", "lede": Inline, "blocks": [Block]}]
    }

Inline = [{"text": str, "bold"?: true, "ui"?: true, "kbd"?: true, "code"?: true,
           "small"?: true, "link"?: url, "br"?: true}]
Block  = {"type": "paragraph", "inline", "fine"?: true}
       | {"type": "heading", "inline"}
       | {"type": "list", "items": [Inline]}            (bullets)
       | {"type": "steps", "items": [[Block]]}          (numbered; each step may hold blocks)
       | {"type": "table", "header": [Inline], "rows": [[Inline]]}
       | {"type": "callout", "style": "tip"|"note"|"warn", "label": str, "blocks": [Block]}
       | {"type": "figure", "image": "03-study.png", "device": "phone"|"watch", "caption": str}
       | {"type": "feature", "figure": Figure|null, "mock": Mock|null, "blocks": [Block], "flip"?: true}
       | {"type": "mock", …Mock}
Mock   = {"bar": {"leading", "title", "trailing"}, "sections": [
            {"header"?: str, "rows": [{"text", "detail"?, "icon"?, "checked"?: true,
                                       "style": "plain"|"link"|"field"|"destructive", "highlight"?: true}],
             "footer"?: str}]}
"""
from __future__ import annotations

import re
from html.parser import HTMLParser

VOID = {"br", "img", "hr", "meta", "input"}


class Node:
    def __init__(self, tag: str, attrs: dict[str, str], parent: "Node | None"):
        self.tag, self.attrs, self.parent = tag, attrs, parent
        self.children: list["Node | str"] = []

    @property
    def classes(self) -> set[str]:
        return set(self.attrs.get("class", "").split())

    def elements(self) -> list["Node"]:
        """Child elements, less those `data-only` keeps out of the edition being written."""
        return [c for c in self.children if isinstance(c, Node) and not pdf_only(c)]

    def find(self, tag: str | None = None, cls: str | None = None) -> "Node | None":
        for c in self.elements():
            if (tag is None or c.tag == tag) and (cls is None or cls in c.classes):
                return c
            found = c.find(tag, cls)
            if found:
                return found
        return None

    def text(self) -> str:
        return "".join(c if isinstance(c, str) else "" if pdf_only(c) else c.text() for c in self.children)


class Parser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.root = Node("root", {}, None)
        self.cur = self.root

    def handle_starttag(self, tag, attrs):
        node = Node(tag, {k: v or "" for k, v in attrs}, self.cur)
        self.cur.children.append(node)
        if tag not in VOID:
            self.cur = node

    def handle_startendtag(self, tag, attrs):
        self.cur.children.append(Node(tag, {k: v or "" for k, v in attrs}, self.cur))

    def handle_endtag(self, tag):
        node = self.cur
        while node is not None and node.tag != tag:
            node = node.parent
        if node is not None and node.parent is not None:
            self.cur = node.parent

    def handle_data(self, data):
        self.cur.children.append(data)


def squash(s: str) -> str:
    return re.sub(r"\s+", " ", s)


# MARK: Inline

# `data-only` marks a passage for some outputs only; it may list several, space-separated
# (docs/manual/README.md). A marked passage is kept where any of its tokens is in the target's set:
#   pdf      the website's PDF only — e.g. links the apps can't carry
#   app      every app, not the PDF
#   apple    every Apple device (and the PDF, which reads as the iPhone edition)
#   iphone / ipad / mac / android   that device's app only (iphone also in the PDF)
TARGETS = {
    "pdf": {"pdf", "apple", "iphone"},
    "iphone": {"app", "apple", "iphone"},
    "ipad": {"app", "apple", "ipad"},
    "mac": {"app", "apple", "mac"},
    "android": {"app", "android"},
}
TOKENS = set().union(*TARGETS.values())
_target = "iphone"


def kept(node: "Node | str", target: str) -> bool:
    if not isinstance(node, Node) or "data-only" not in node.attrs:
        return True
    tokens = set(node.attrs["data-only"].split())
    unknown = tokens - TOKENS
    if unknown:
        raise ValueError(f"unknown data-only token(s): {', '.join(sorted(unknown))}")
    return bool(tokens & TARGETS[target])


def pdf_only(node: "Node | str") -> bool:
    """True for a node the current target's package leaves out."""
    return not kept(node, _target)


def inline(node: Node, marks: dict | None = None) -> list[dict]:
    return merge(trim(runs(node, marks)))


def runs(node: Node, marks: dict | None = None) -> list[dict]:
    """A node's text runs, untrimmed: the space at the edge of a nested span (`file<span> in the
    picker</span>`) is part of the sentence."""
    marks = dict(marks or {})
    out: list[dict] = []
    for c in node.children:
        if pdf_only(c):
            continue
        if isinstance(c, str):
            t = squash(c)
            if t:
                out.append({"text": t, **marks})
            continue
        m = dict(marks)
        if c.tag in ("b", "strong"):
            m["bold"] = True
        elif c.tag == "span" and "ui" in c.classes:
            m["ui"] = True
        elif c.tag == "span" and "tag" in c.classes:
            m["ui"] = True
            m["bold"] = True
        elif c.tag == "kbd":
            m["kbd"] = True
        elif c.tag == "code":
            m["code"] = True
        elif c.tag == "small":
            m["small"] = True
        elif c.tag == "a":
            m["link"] = c.attrs.get("href", "")
        elif c.tag == "br":
            out.append({"text": "\n", "br": True})
            continue
        elif c.tag in ("ul", "ol", "div", "p", "table"):
            continue  # block content inside an inline context is collected by the caller
        out += runs(c, m)
    return out


def merge(runs: list[dict]) -> list[dict]:
    merged: list[dict] = []
    for r in runs:
        if merged and {k: v for k, v in merged[-1].items() if k != "text"} == \
                {k: v for k, v in r.items() if k != "text"} and not r.get("br"):
            merged[-1] = {**merged[-1], "text": merged[-1]["text"] + r["text"]}
        else:
            merged.append(r)
    return merged


def trim(runs: list[dict]) -> list[dict]:
    runs = [dict(r) for r in runs]
    # One space where two runs meet ("a " + " b"), as HTML collapses it.
    for prev, r in zip(runs, runs[1:]):
        if prev["text"].endswith(" ") and r["text"].startswith(" "):
            r["text"] = r["text"].lstrip(" ")
    while runs and not runs[0]["text"].strip() and not runs[0].get("br"):
        runs.pop(0)
    while runs and not runs[-1]["text"].strip() and not runs[-1].get("br"):
        runs.pop()
    if runs:
        runs[0]["text"] = runs[0]["text"].lstrip()
        runs[-1]["text"] = runs[-1]["text"].rstrip()
    # Around a line break, the HTML's indentation isn't text.
    for i, r in enumerate(runs):
        if r.get("br"):
            if i > 0:
                runs[i - 1]["text"] = runs[i - 1]["text"].rstrip()
            if i + 1 < len(runs):
                runs[i + 1]["text"] = runs[i + 1]["text"].lstrip()
    return [r for r in runs if r["text"] or r.get("br")]


def plain(node: Node | None) -> str:
    return squash(node.text()).strip() if node else ""


# MARK: Blocks

def blocks(node: Node) -> list[dict]:
    out: list[dict] = []
    loose: list[Node | str] = []

    def flush():
        if not loose:
            return
        holder = Node("p", {}, None)
        holder.children = list(loose)
        runs = inline(holder)
        if runs:
            out.append({"type": "paragraph", "inline": runs})
        loose.clear()

    for c in node.children:
        if pdf_only(c):
            continue
        if isinstance(c, str) or c.tag in ("b", "strong", "span", "a", "kbd", "code", "small", "br") \
                and "note-label" not in c.classes:
            loose.append(c)
            continue
        flush()
        b = block(c)
        if b is not None:
            out += b if isinstance(b, list) else [b]
    flush()
    return out


def block(n: Node):
    if pdf_only(n):
        return None
    cls = n.classes
    if n.tag == "p":
        runs = inline(n)
        if not runs:
            return None
        b = {"type": "paragraph", "inline": runs}
        if "closing" in cls:
            b["fine"] = True
        return b
    if n.tag == "h3":
        return {"type": "heading", "inline": inline(n)}
    if n.tag == "ul":
        # An item whose words are all for other editions leaves nothing behind.
        return {"type": "list", "items": [r for r in (inline(li) for li in n.elements() if li.tag == "li") if r]}
    if n.tag == "ol":
        items = []
        for li in n.elements():
            if li.tag != "li":
                continue
            inner = blocks(li)
            items.append(inner or [{"type": "paragraph", "inline": inline(li)}])
        return {"type": "steps", "items": items}
    if n.tag == "table":
        rows = [r for r in iter_rows(n)]
        header, body = [], []
        for r in rows:
            cells = [c for c in r.elements() if c.tag in ("th", "td")]
            if cells and all(c.tag == "th" for c in cells):
                header = [inline(c) for c in cells]
            else:
                body.append([inline(c) for c in cells])
        return {"type": "table", "header": header, "rows": body}
    if n.tag == "div" and cls & {"tip", "note", "warn"}:
        label = n.find("span", "note-label")
        style = "tip" if "tip" in cls else "warn" if "warn" in cls else "note"
        body = Node("div", {}, None)
        body.children = [c for c in n.children if c is not label]
        return {"type": "callout", "style": style, "label": plain(label), "blocks": blocks(body)}
    if n.tag == "div" and "feature" in cls:
        fig = next((c for c in n.elements() if c.tag == "figure"), None)
        mock = next((c for c in n.elements() if "mock" in c.classes), None)
        text = next((c for c in n.elements() if "text" in c.classes), None)
        b = {"type": "feature", "figure": figure(fig) if fig else None,
             "mock": mock_block(mock) if mock else None, "blocks": blocks(text) if text else []}
        if "flip" in cls:
            b["flip"] = True
        return b
    if n.tag == "figure":
        return figure(n)
    if n.tag == "div" and "mock" in cls:
        return {"type": "mock", **mock_block(n)}
    if n.tag in ("div", "section"):
        return blocks(n)
    return None


def iter_rows(table: Node):
    for c in table.elements():
        if c.tag == "tr":
            yield c
        elif c.tag in ("thead", "tbody"):
            yield from iter_rows(c)


def figure(n: Node) -> dict:
    img = n.find("img")
    src = img.attrs.get("src", "") if img else ""
    return {"type": "figure", "image": src.split("/")[-1],
            "device": "watch" if img and "watch" in img.classes else "phone",
            "caption": plain(n.find("figcaption"))}


def mock_block(n: Node) -> dict:
    bar = n.find("div", "bar")
    spans = [c for c in bar.elements() if c.tag == "span"] if bar else []
    leading = plain(spans[0]) if len(spans) > 0 else ""
    title = plain(spans[1]) if len(spans) > 1 else ""
    trailing = plain(spans[2]) if len(spans) > 2 else ""
    sections: list[dict] = []
    current: dict | None = None
    for c in n.elements():
        if c is bar:
            continue
        if "head" in c.classes:
            current = {"header": plain(c), "rows": []}
            sections.append(current)
        elif "group" in c.classes:
            if current is None or current["rows"]:
                current = {"rows": []}
                sections.append(current)
            current["rows"] = [mock_row(r) for r in c.elements() if "row" in r.classes]
        elif "foot" in c.classes and sections:
            sections[-1]["footer"] = plain(c)
    return {"bar": {"leading": leading, "title": title, "trailing": trailing}, "sections": sections}


def mock_row(r: Node) -> dict:
    cls = r.classes
    icon = r.find("span", "ico")
    check = r.find("span", "check")
    sub = r.find("span", "sub")
    # The label is the row's own text, less its icon, check mark and detail line.
    skip = {id(x) for x in (icon, check, sub) if x is not None}

    def own(node: Node) -> str:
        return "".join(c if isinstance(c, str) else ("" if id(c) in skip or pdf_only(c) else own(c))
                       for c in node.children)

    style = "link" if "link" in cls else "field" if "field" in cls else "plain"
    if "color:#c33" in r.attrs.get("style", "").replace(" ", ""):
        style = "destructive"
    row = {"text": squash(own(r)).strip(), "style": style}
    if sub:
        row["detail"] = plain(sub)
    if icon:
        row["icon"] = plain(icon)
    if check:
        row["checked"] = True
    if "hl" in cls:
        row["highlight"] = True
    return row


# MARK: Document

def convert(html: str, language: str, platform: str = "iphone") -> dict:
    """`platform`: iphone, ipad, mac or android — which device's edition to write."""
    global _target
    if platform not in TARGETS or platform == "pdf":
        raise ValueError(f"platform must be one of iphone, ipad, mac, android")
    _target = platform
    title = re.search(r"<!--\s*title:\s*(.*?)\s*-->", html)
    p = Parser()
    p.feed(html)
    root = p.root
    cover = root.find("div", "cover")
    toc = root.find("div", "toc")
    summaries = []
    if toc:
        for li in (toc.find("ol") or toc).elements():
            span = li.find("span")
            summaries.append(plain(span))
    chapters = []
    for i, sec in enumerate(c for c in root.elements() if c.tag == "section" and "chapter" in c.classes):
        h2 = sec.find("h2")
        lede = next((c for c in sec.elements() if c.tag == "p" and "lede" in c.classes), None)
        body = Node("section", {}, None)
        body.children = [c for c in sec.children if c is not h2 and c is not lede]
        chapters.append({
            "title": plain(h2),
            "summary": summaries[i] if i < len(summaries) else "",
            "lede": inline(lede) if lede else [],
            "blocks": blocks(body),
        })
    return {
        "schema": 1,
        "language": language,
        "title": title.group(1) if title else "Scripture Alone",
        "cover": {
            "eyebrow": plain(cover.find("div", "eyebrow")) if cover else "",
            "title": plain(cover.find("h1")) if cover else "",
            "subtitle": plain(cover.find("div", "subtitle")) if cover else "",
            "edition": plain(cover.find("div", "edition")) if cover else "",
            "images": [i.attrs.get("src", "").split("/")[-1]
                       for i in (cover.find("div", "phones").elements() if cover and cover.find("div", "phones") else [])],
        },
        "contents": plain(toc.find("h2")) if toc else "",
        "chapters": chapters,
    }


def images_used(doc: dict) -> set[str]:
    found = set(doc["cover"]["images"])

    def walk(bs):
        for b in bs:
            if b["type"] == "figure":
                found.add(b["image"])
            elif b["type"] == "feature":
                if b.get("figure"):
                    found.add(b["figure"]["image"])
                walk(b["blocks"])
            elif b["type"] == "callout":
                walk(b["blocks"])
            elif b["type"] == "steps":
                for item in b["items"]:
                    walk(item)

    for ch in doc["chapters"]:
        walk(ch["blocks"])
    return found
