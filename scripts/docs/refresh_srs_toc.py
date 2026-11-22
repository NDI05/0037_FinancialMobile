#!/usr/bin/env python3
"""Refresh the baseline's static DOCX TOC against its rendered PDF page numbers."""
from argparse import ArgumentParser
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
from lxml import etree as E
from pypdf import PdfReader

NS = {'w': 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'}


def normalized(s):
    return ''.join(c.casefold() for c in s if c.isalnum())


def main():
    ap = ArgumentParser(); ap.add_argument('--docx', type=Path, required=True)
    ap.add_argument('--pdf', type=Path, required=True); args = ap.parse_args()
    pages = [normalized(p.extract_text()) for p in PdfReader(args.pdf).pages]
    first_body = next(i for i,p in enumerate(pages) if normalized('This Software Requirements Specification defines PAM') in p)
    with ZipFile(args.docx) as z:
        files = {n:z.read(n) for n in z.namelist()}
    root = E.fromstring(files['word/document.xml']); changes = 0; unmatched = []
    for p in root.xpath('//w:p', namespaces=NS):
        styles = p.xpath('./w:pPr/w:pStyle/@w:val', namespaces=NS)
        if not styles or not styles[0].startswith('TOC'): continue
        texts = p.xpath('.//w:t', namespaces=NS)
        if len(texts)<2 or not (texts[-1].text or '').isdigit(): continue
        title = ''.join(t.text or '' for t in texts[:-1])
        matches = [i+1 for i,s in enumerate(pages) if i>=first_body and normalized(title) in s]
        if not matches:
            unmatched.append(title); continue
        number = str(matches[0])
        if texts[-1].text != number: changes += 1
        texts[-1].text = number
    if unmatched:
        raise SystemExit('TOC headings not found: '+repr(unmatched))
    files['word/document.xml']=E.tostring(root,xml_declaration=True,encoding='UTF-8',standalone=True)
    with ZipFile(args.docx,'w',compression=ZIP_DEFLATED) as z:
        for name,data in files.items():z.writestr(name,data)
    print('Updated TOC references:',changes,'; rendered pages:',len(pages))


if __name__ == '__main__':main()
