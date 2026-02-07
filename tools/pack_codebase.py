import os
import datetime

# Configuration
SOURCE_DIRS = [
    r"app\src\main\kotlin",
    r"app\src\main\res\layout",
    r"app\src\main\AndroidManifest.xml"
]
OUTPUT_FILE = "CODEBASE_DUMP.md"
EXTENSIONS = {".kt", ".xml", ".gradle", ".kts"}

def pack_codebase():
    repo_root = os.getcwd()
    timestamp = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    
    with open(OUTPUT_FILE, "w", encoding="utf-8") as outfile:
        outfile.write(f"# Oracle_OS Codebase Dump\n")
        outfile.write(f"Generated: {timestamp}\n\n")
        
        # 1. Tree Structure (Simplified)
        outfile.write("## File Structure\n```text\n")
        for root, dirs, files in os.walk("."):
            if "build" in root or ".git" in root or ".gradle" in root:
                continue
            level = root.replace(repo_root, '').count(os.sep)
            indent = ' ' * 4 * (level)
            outfile.write('{}{}/\n'.format(indent, os.path.basename(root)))
            subindent = ' ' * 4 * (level + 1)
            for f in files:
                if any(f.endswith(ext) for ext in EXTENSIONS) or f in ["AndroidManifest.xml", "build.gradle.kts"]:
                     outfile.write('{}{}\n'.format(subindent, f))
        outfile.write("```\n\n")
        
        # 2. File Contents
        outfile.write("## Source Code\n")
        
        for source_path in SOURCE_DIRS:
            full_path = os.path.join(repo_root, source_path)
            
            # Handle single file (Manifest)
            if os.path.isfile(full_path):
                 write_file(full_path, outfile, repo_root)
                 continue

            if not os.path.exists(full_path):
                print(f"Skipping missing path: {full_path}")
                continue

            for root, _, files in os.walk(full_path):
                for file in files:
                    if any(file.endswith(ext) for ext in EXTENSIONS):
                        file_path = os.path.join(root, file)
                        write_file(file_path, outfile, repo_root)

    print(f"✅ Codebase packed into {OUTPUT_FILE} ({os.path.getsize(OUTPUT_FILE)//1024} KB)")

def write_file(path, outfile, repo_root):
    rel_path = os.path.relpath(path, repo_root)
    ext = os.path.splitext(path)[1][1:] # kt, xml
    
    print(f"Packing: {rel_path}")
    outfile.write(f"### `{rel_path}`\n")
    outfile.write(f"```{ext}\n")
    try:
        with open(path, "r", encoding="utf-8") as infile:
            outfile.write(infile.read())
    except Exception as e:
        outfile.write(f"// Error reading file: {e}")
    outfile.write("\n```\n\n")

if __name__ == "__main__":
    pack_codebase()
