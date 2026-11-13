#!/usr/bin/env python3
"""Prints a release's notes: tools/release-notes.md with the version's CHANGELOG.md section and the
APK size filled in.

usage: tools/release_notes.py <version> <apk>
"""
import os
import re
import sys

version, apk = sys.argv[1], sys.argv[2]
template = open("tools/release-notes.md", encoding="utf-8").read()
changelog = open("CHANGELOG.md", encoding="utf-8").read()
m = re.search(rf"^## {re.escape(version)} .*?\n(.*?)(?=^## |\Z)", changelog, re.S | re.M)
if not m:
    sys.exit(f"CHANGELOG.md has no '## {version}' section")
notes = (template.replace("{{VERSION}}", version)
         .replace("{{CHANGES}}", m.group(1).strip())
         .replace("{{SIZE}}", "%.1f MB" % (os.path.getsize(apk) / 1048576)))
left = re.findall(r"\{\{\w+\}\}", notes)
if left:
    sys.exit(f"unfilled placeholders: {left}")
print(notes.strip())
