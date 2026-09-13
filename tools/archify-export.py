#!/usr/bin/env python3
"""Turn an Archify HTML artifact into a standalone SVG and a Markdown page.

Archify delivers one self-contained HTML file: an interactive viewer with the
diagram as inline SVG, styled by a large stylesheet and driven by JavaScript.
GitLab renders neither - it shows Markdown and static images - so the parts that
survive have to be lifted out:

    archify HTML  ->  <name>.svg   the diagram alone, styles inlined
                  ->  <name>.md    a page that embeds it, with the cards as text

Why the styles have to be inlined: the SVG carries class names and nothing else,
and every colour lives in CSS variables in the page's stylesheet. Extracted on
its own it renders as black shapes on white. So the rules the SVG actually uses
are copied into a <style> inside it, and the viewer's own chrome - toolbar,
menus, cards, buttons - is left behind.

Light is the default rather than Archify's dark, because a diagram in a GitLab
page sits on a white background; a dark-mode block follows for readers whose
browser asks for it.

    tools/archify-export.py docs/architecture/html/c4-context.html docs/architecture

Run it again after re-delivering a diagram; it is deterministic.
"""
import html as html_lib
import json
import re
import sys
from pathlib import Path

# Viewer chrome: these rules style the page around the diagram, never the
# diagram, and dragging them along would be most of the 186 KB.
CHROME = re.compile(
    r'(^|[\s,>+~])(body|html|\.toolbar|\.header|\.cards|\.card\b|\.menu|\.btn|'
    r'\.badge|\.chip|\.panel\b|\.search|\.legend\b|\.tooltip|\.hint|\.footer|'
    r'\.share|\.view-|\.trace-|\.export|\.dialog|\.overlay|#)')

SVG_ELEMENTS = {'svg', 'g', 'rect', 'circle', 'ellipse', 'line', 'path', 'polygon',
                'polyline', 'text', 'tspan', 'marker', 'defs', 'use', 'image',
                'filter', 'clipPath', 'linearGradient', 'stop', 'pattern', 'title', 'desc'}


def blocks(css):
    """Yields (selector, body) pairs, descending into at-rules that nest."""
    depth, start, i = 0, 0, 0
    while i < len(css):
        ch = css[i]
        if ch == '{':
            if depth == 0:
                selector = css[start:i].strip()
                body_start = i + 1
            depth += 1
        elif ch == '}':
            depth -= 1
            if depth == 0:
                yield selector, css[body_start:i]
                start = i + 1
        i += 1


def used_names(svg):
    classes = set()
    for attr in re.findall(r'class="([^"]*)"', svg):
        classes.update(attr.split())
    elements = {m.group(1) for m in re.finditer(r'<([A-Za-z][\w:-]*)', svg)}
    return classes, elements & SVG_ELEMENTS


def wanted(selector, classes, elements):
    """True when a rule can affect the extracted diagram."""
    if selector.startswith('@font-face'):
        return False                      # a web font GitLab will not fetch
    for part in selector.split(','):
        part = part.strip()
        if not part or CHROME.search(part):
            continue
        if any(f'.{name}' in part for name in classes):
            return True
        bare = re.sub(r'\[[^\]]*\]|:[\w-]+(\([^)]*\))?', '', part).strip()
        if bare in elements:
            return True
    return False


def theme_variables(css, theme):
    """The variable block for one theme, as declarations."""
    for selector, body in blocks(css):
        if theme == 'dark' and selector.startswith(':root'):
            return body
        if theme == 'light' and '[data-theme="light"]' in selector \
                and 'data-preset' not in selector:
            return body
    return ''


def diagram_rules(css, classes, elements):
    out = []
    for selector, body in blocks(css):
        if selector.startswith('@media') or selector.startswith('@supports'):
            continue                      # viewer responsiveness, not diagram style
        if selector.startswith(':root') or '[data-theme' in selector:
            continue                      # variables handled separately
        if wanted(selector, classes, elements):
            # Preset-specific prefixes never match a bare extracted SVG.
            cleaned = re.sub(r'\[data-(theme|preset|embed)="[^"]*"\]\s*', '', selector)
            out.append(f'{cleaned} {{{body}}}')
    return out


def to_svg(page):
    svg = page[page.index('<svg'):page.rindex('</svg>') + len('</svg>')]
    css = re.findall(r'<style[^>]*>(.*?)</style>', page, re.S)[-1]
    classes, elements = used_names(svg)

    width, height = re.search(r'viewBox="0 0 ([\d.]+) ([\d.]+)"', svg).groups()
    style = (
        ':root{' + theme_variables(css, 'light') + '}\n'
        '@media (prefers-color-scheme: dark){:root{'
        + theme_variables(css, 'dark') + '}}\n'
        # The viewer's font is a web font; name the same shapes generically.
        'text{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}\n'
        + '\n'.join(diagram_rules(css, classes, elements))
    )

    svg = svg.replace(
        '<svg',
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}"', 1)
    return svg.replace('</title>', '</title>\n<style>\n' + style + '\n</style>', 1)


def to_markdown(page, spec, svg_name, title):
    lines = [f'# {title}', '',
             f'![{title}]({svg_name})', '']

    # The viewer shows these beside the diagram; in Markdown they are the prose
    # that would otherwise be lost with the interactivity.
    for card in spec.get('cards', []):
        lines.append(f'**{card["title"]}**')
        lines.append('')
        lines.extend(f'- {item}' for item in card.get('items', []))
        lines.append('')

    lines += ['---', '',
              f'Generated from `{spec.get("diagram_type")}` source by Archify, then '
              'exported with `tools/archify-export.py`. Edit the JSON under '
              '`docs/architecture/src/`, re-deliver, and re-export — never edit the '
              'SVG by hand.', '']
    return '\n'.join(lines)


def main(argv):
    if len(argv) != 3:
        raise SystemExit(__doc__)
    page_path, out_dir = Path(argv[1]), Path(argv[2])
    page = page_path.read_text(encoding='utf-8')

    name = page_path.stem
    spec_path = next(Path('docs/architecture/src').glob(f'{name}.*.json'), None)
    spec = json.loads(spec_path.read_text(encoding='utf-8')) if spec_path else {}
    title = html_lib.unescape(
        re.search(r'<title[^>]*>(.*?)</title>', page, re.S).group(1)).strip()

    (out_dir / 'svg').mkdir(parents=True, exist_ok=True)
    svg_path = out_dir / 'svg' / f'{name}.svg'
    svg_path.write_text(to_svg(page), encoding='utf-8')

    md_path = out_dir / f'{name}.md'
    md_path.write_text(to_markdown(page, spec, f'svg/{name}.svg', title), encoding='utf-8')
    print(f'{svg_path} ({svg_path.stat().st_size} bytes)\n{md_path}')


if __name__ == '__main__':
    main(sys.argv)
