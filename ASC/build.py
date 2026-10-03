#!/usr/bin/env python3
"""
ASC Metadata Builder for Edendale.

Reads platform metadata review files from ASC/{iOS,macOS,tvOS,visionOS}/*.json
and builds the canonical App Store Connect CLI metadata tree under:
    ASC/.build/metadata/<platform>/
        ├── app-info/
        │   └── <locale>.json
        └── version/<version>/
            └── <locale>.json

Validates all field character limits and runs `asc metadata validate` for each platform.
"""

import json
import os
import shutil
import subprocess
import sys
import glob

def find_asc():
    # Check PATH or common Homebrew / system paths
    for candidate in ["asc", "/opt/homebrew/bin/asc", "/usr/local/bin/asc"]:
        path = shutil.which(candidate)
        if path:
            return path
        if os.path.isfile(candidate) and os.access(candidate, os.X_OK):
            return candidate
    return None

def build():
    base_dir = os.path.dirname(os.path.abspath(__file__))
    config_path = os.path.join(base_dir, "config.json")
    
    if not os.path.isfile(config_path):
        print(f"Error: {config_path} not found.", file=sys.stderr)
        sys.exit(1)
        
    with open(config_path, "r", encoding="utf-8") as f:
        config = json.load(f)
        
    version_str = config.get("versionString", "27.0")
    field_limits = config.get("fieldLimits", {
        "name": 30,
        "subtitle": 30,
        "promotionalText": 170,
        "keywords": 100,
        "description": 4000,
        "whatsNew": 4000
    })
    
    build_metadata_root = os.path.join(base_dir, ".build", "metadata")
    if os.path.exists(build_metadata_root):
        shutil.rmtree(build_metadata_root)
        
    platforms = [p["dir"] for p in config.get("platforms", [
        {"dir": "iOS"}, {"dir": "macOS"}, {"dir": "tvOS"}, {"dir": "visionOS"}
    ])]
    
    validation_errors = []
    total_files = 0
    
    print(f"=== Building ASC Metadata for Edendale v{version_str} ===")
    
    for platform in platforms:
        src_dir = os.path.join(base_dir, platform)
        if not os.path.isdir(src_dir):
            print(f"Warning: Source directory {src_dir} does not exist, skipping.")
            continue
            
        dest_app_info_dir = os.path.join(build_metadata_root, platform, "app-info")
        dest_version_dir = os.path.join(build_metadata_root, platform, "version", version_str)
        
        os.makedirs(dest_app_info_dir, exist_ok=True)
        os.makedirs(dest_version_dir, exist_ok=True)
        
        locale_files = sorted(glob.glob(os.path.join(src_dir, "*.json")))
        print(f"\nProcessing {platform}: {len(locale_files)} locales")
        
        for file_path in locale_files:
            locale = os.path.basename(file_path).replace(".json", "")
            with open(file_path, "r", encoding="utf-8") as f:
                data = json.load(f)
                
            # Verify field limits
            for field, limit in field_limits.items():
                val = data.get(field, "")
                if len(val) > limit:
                    msg = f"[{platform}/{locale}] '{field}' exceeds limit: {len(val)} > {limit}"
                    validation_errors.append(msg)
                    print(f"  ERROR: {msg}")

            # App-info payload
            app_info = {
                "name": data.get("name", ""),
                "subtitle": data.get("subtitle", ""),
                "privacyPolicyUrl": data.get("privacyPolicyUrl", "")
            }
            if data.get("privacyPolicyText"):
                app_info["privacyPolicyText"] = data["privacyPolicyText"]

            # Version payload
            version_payload = {
                "description": data.get("description", ""),
                "keywords": data.get("keywords", ""),
                "promotionalText": data.get("promotionalText", ""),
                "whatsNew": data.get("whatsNew", ""),
                "marketingUrl": data.get("marketingUrl", ""),
                "supportUrl": data.get("supportUrl", "")
            }
            
            # Write out canonical JSON files
            app_info_file = os.path.join(dest_app_info_dir, f"{locale}.json")
            with open(app_info_file, "w", encoding="utf-8") as f:
                json.dump(app_info, f, indent=2, ensure_ascii=False)
                f.write("\n")
                
            version_file = os.path.join(dest_version_dir, f"{locale}.json")
            with open(version_file, "w", encoding="utf-8") as f:
                json.dump(version_payload, f, indent=2, ensure_ascii=False)
                f.write("\n")
                
            total_files += 2

    print(f"\n✓ Generated {total_files} files across {len(platforms)} platforms in {build_metadata_root}")
    
    if validation_errors:
        print(f"\n❌ Validation failed with {len(validation_errors)} error(s):", file=sys.stderr)
        for err in validation_errors:
            print(f"  - {err}", file=sys.stderr)
        sys.exit(1)
        
    # Run asc metadata validate if asc CLI is installed
    asc_bin = find_asc()
    if asc_bin:
        print(f"\n=== Validating with asc CLI ({asc_bin}) ===")
        all_passed = True
        for platform in platforms:
            platform_meta_dir = os.path.join(build_metadata_root, platform)
            cmd = [asc_bin, "metadata", "validate", "--dir", platform_meta_dir]
            res = subprocess.run(cmd, capture_output=True, text=True)
            if res.returncode == 0:
                print(f"  ✓ {platform}: PASSED")
            else:
                print(f"  ❌ {platform}: FAILED (code {res.returncode})")
                if res.stdout:
                    print(res.stdout)
                if res.stderr:
                    print(res.stderr)
                all_passed = False
                
        if not all_passed:
            print("\n❌ One or more platforms failed asc metadata validation.", file=sys.stderr)
            sys.exit(1)
        else:
            print("\n✓ All platforms passed asc metadata validate cleanly!")
    else:
        print("\nNote: `asc` CLI not found on PATH. Metadata built successfully, but CLI validation skipped.")

    print("\nNext steps:")
    print(f"  # Preview changes on App Store Connect:")
    for p in config.get("platforms", []):
        plat = p["platform"]
        pdir = p["dir"]
        print(f"  asc metadata apply --app {config.get('appId')} --version {version_str} --platform {plat} --dir ./ASC/.build/metadata/{pdir} --dry-run")
    print(f"\n  # Apply changes without --dry-run when ready.")

if __name__ == "__main__":
    build()
