"""Render the existing Android VectorDrawable paths unchanged into a Windows ICO."""
import io
from pathlib import Path
import xml.etree.ElementTree as ET
import cairosvg
from PIL import Image
root = Path(__file__).resolve().parents[1]
a = '{http://schemas.android.com/apk/res/android}'
vector = ET.parse(root / 'app/src/main/res/drawable/ic_monitor.xml').getroot()
svg = ET.Element('svg', xmlns='http://www.w3.org/2000/svg', viewBox='0 0 108 108')
for path in vector.findall('path'):
    attrs = {'d': path.attrib[a+'pathData']}
    for src, dst in [('fillColor','fill'),('strokeColor','stroke'),('strokeWidth','stroke-width'),('strokeLineCap','stroke-linecap')]:
        if a+src in path.attrib:
            val = path.attrib[a+src]
            attrs[dst] = 'none' if val == '@android:color/transparent' else val
    ET.SubElement(svg, 'path', attrs)
png = cairosvg.svg2png(bytestring=ET.tostring(svg), output_width=256, output_height=256)
Image.open(io.BytesIO(png)).save(root / 'windows/monitor.ico', sizes=[(16,16),(24,24),(32,32),(48,48),(64,64),(128,128),(256,256)])
