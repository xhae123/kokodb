#!/usr/bin/env python3
"""Generate the editable KoKoDB monogram and README wordmark without font downloads."""

from pathlib import Path

HERE = Path(__file__).resolve().parent


def mark(light=False):
    color = "#ffffff" if light else "#7c3aed"
    return f'''<g fill="{color}">
    <path d="M55 40H88Q96 40 96 48V105H106L161 50Q167 44 176 44H215L138 121Q132 127 124 127H55Q47 127 47 119V48Q47 40 55 40Z"/>
    <path d="M55 141H125Q133 141 139 147L208 216H166Q158 216 152 210L96 154V208Q96 216 88 216H55Q47 216 47 208V149Q47 141 55 141Z"/>
  </g>'''


def write(name, width, height, content, title, description):
    (HERE / name).write_text(f'''<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}" role="img" aria-labelledby="title desc">
  <title id="title">{title}</title>
  <desc id="desc">{description}</desc>
{content}
</svg>
''')


def main():
    for name, light in (("logo.svg", False), ("logo-light.svg", True)):
        write(name, 256, 256, mark(light), "KoKoDB monogram",
              "Two separated, folded row bands form the KoKoDB K monogram.")
    write("logo-tile.svg", 256, 256,
          '<rect width="256" height="256" rx="60" fill="#251044"/>' + mark(True),
          "KoKoDB app icon", "The KoKoDB K monogram on a deep violet rounded square.")
    cover = '<g transform="translate(-29 0) scale(.625)">' + mark() + '''</g>
    <text x="127" y="115" fill="#7c3aed" font-family="Arial, Helvetica, sans-serif"
          font-size="90" font-weight="700" letter-spacing="-3">KoKoDB</text>'''
    write("hero.svg", 500, 160, cover, "KoKoDB",
          "The KoKoDB monogram and wordmark in solid violet on a transparent background.")



if __name__ == "__main__":
    main()
