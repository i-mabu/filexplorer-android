#!/usr/bin/env python3
"""Compose 依存ライブラリの最小 Maven リゾルバ。

ルートアーティファクトの POM を読み、compile / runtime スコープの依存を
たどって aar / jar を libs/ へダウンロードする。最後に解決順のファイル名
一覧を libs/artifacts.txt に書き出す (build-apk.sh が読む)。

リポジトリは Google Maven (androidx 系) を先に、Maven Central を次に試す。
バージョンの競合は「最初に到達したものを採用」を基本とし、ファミリー
単位で揃える必要があるものだけ PINNED で固定する。
"""
import os
import subprocess
import sys
import xml.etree.ElementTree as ET
from collections import deque

ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LIBS_DIR = os.path.join(ROOT_DIR, "libs")
POM_CACHE = os.path.join(ROOT_DIR, "build", "pomcache")
os.makedirs(LIBS_DIR, exist_ok=True)
os.makedirs(POM_CACHE, exist_ok=True)

REPOSITORIES = [
    "https://dl.google.com/dl/android/maven2",
    "https://repo1.maven.org/maven2",
]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}

# 解決の起点 (Compose 1.7.6 / Material3 1.3.1 / activity-compose 1.9.3)
ROOT_ARTIFACTS = [
    ("androidx.compose.ui", "ui", "1.7.6"),
    ("androidx.compose.ui", "ui-graphics", "1.7.6"),
    ("androidx.compose.ui", "ui-text", "1.7.6"),
    ("androidx.compose.ui", "ui-unit", "1.7.6"),
    ("androidx.compose.foundation", "foundation", "1.7.6"),
    ("androidx.compose.foundation", "foundation-layout", "1.7.6"),
    ("androidx.compose.runtime", "runtime", "1.7.6"),
    ("androidx.compose.runtime", "runtime-saveable", "1.7.6"),
    ("androidx.compose.material3", "material3", "1.3.1"),
    ("androidx.activity", "activity-compose", "1.9.3"),
]

# ファミリー内で版を揃えるための固定 (Gradle のプラットフォーム相当)
PINNED = {
    ("androidx.lifecycle", "lifecycle-common"): "2.8.7",
    ("androidx.lifecycle", "lifecycle-common-java8"): "2.8.7",
    ("androidx.lifecycle", "lifecycle-runtime"): "2.8.7",
    ("androidx.lifecycle", "lifecycle-runtime-ktx"): "2.8.7",
    ("androidx.lifecycle", "lifecycle-viewmodel"): "2.8.7",
    ("androidx.lifecycle", "lifecycle-viewmodel-ktx"): "2.8.7",
    ("androidx.lifecycle", "lifecycle-viewmodel-savedstate"): "2.8.7",
    ("androidx.lifecycle", "lifecycle-process"): "2.8.7",
    ("androidx.lifecycle", "lifecycle-runtime-compose"): "2.8.7",
    ("androidx.core", "core"): "1.13.1",
    ("androidx.core", "core-ktx"): "1.13.1",
    ("androidx.arch.core", "core-common"): "2.2.0",
    ("androidx.arch.core", "core-runtime"): "2.2.0",
}

# collection-ktx 1.2.0 のクラスは collection-jvm 1.4.0 に統合済みで、
# 両方を dex に入れると duplicate class になるため除外する
EXCLUDED = {("androidx.collection", "collection-ktx")}


def repo_path(group, artifact, version, filename):
    return "{}/{}/{}/{}".format(group.replace(".", "/"), artifact, version, filename)


def download(url, dest=None):
    """curl で取得する。dest があればファイルに保存し、無ければ bytes を返す。"""
    if dest and os.path.exists(dest):
        return dest
    result = subprocess.run(["curl", "-sfL", url], capture_output=True)
    if result.returncode != 0:
        return None
    if dest:
        with open(dest, "wb") as fh:
            fh.write(result.stdout)
        return dest
    return result.stdout


def fetch_pom(group, artifact, version):
    dest = os.path.join(POM_CACHE, "{}_{}_{}.pom".format(group, artifact, version))
    if os.path.exists(dest):
        return dest
    filename = "{}-{}.pom".format(artifact, version)
    for repo in REPOSITORIES:
        if download("{}/{}".format(repo, repo_path(group, artifact, version, filename)), dest):
            return dest
    return None


def child_text(element, tag):
    node = element.find("m:" + tag, NS)
    return node.text.strip() if node is not None and node.text else None


def interpolate(value, properties):
    if value is None:
        return None
    for _ in range(5):
        if "${" not in value:
            break
        for key, val in properties.items():
            value = value.replace("${%s}" % key, val)
    return value


def normalize_version(value):
    if value is None:
        return None
    value = value.strip()
    if value.startswith("[") and value.endswith("]"):
        value = value[1:-1].split(",")[0].strip()
    return value


def parse_pom(group, artifact, version, depth=0):
    """(properties, dependencies, packaging) を返す。
    dependencies は (group, artifact, version, type, scope, optional) の列。"""
    pom_path = fetch_pom(group, artifact, version)
    if not pom_path:
        print("  !! POM not found: {}:{}:{}".format(group, artifact, version))
        return {}, [], "jar"
    root = ET.parse(pom_path).getroot()

    properties = {}
    props_node = root.find("m:properties", NS)
    if props_node is not None:
        for child in props_node:
            tag = child.tag.split("}")[-1]
            properties[tag] = (child.text or "").strip()
    own_version = child_text(root, "version") or version
    properties["project.version"] = own_version
    properties["pom.version"] = own_version

    managed = {}
    parent = root.find("m:parent", NS)
    if parent is not None and depth < 4:
        pg = child_text(parent, "groupId")
        pa = child_text(parent, "artifactId")
        pv = child_text(parent, "version")
        parent_props, _, _ = parse_pom(pg, pa, pv, depth + 1)
        for key, val in parent_props.items():
            properties.setdefault(key, val)

    dm_node = root.find("m:dependencyManagement/m:dependencies", NS)
    if dm_node is not None:
        for dep in dm_node.findall("m:dependency", NS):
            dg = child_text(dep, "groupId")
            da = child_text(dep, "artifactId")
            dv = child_text(dep, "version")
            if dg and da and dv:
                managed[(dg, da)] = interpolate(dv, properties)

    dependencies = []
    deps_node = root.find("m:dependencies", NS)
    if deps_node is not None:
        for dep in deps_node.findall("m:dependency", NS):
            dg = child_text(dep, "groupId")
            da = child_text(dep, "artifactId")
            dv = child_text(dep, "version")
            if dv is None:
                dv = managed.get((dg, da))
            dv = normalize_version(interpolate(dv, properties))
            if (dg, da) in PINNED:
                dv = PINNED[(dg, da)]
            if dg and da and dv:
                dependencies.append((
                    dg, da, dv,
                    child_text(dep, "type") or "jar",
                    child_text(dep, "scope") or "compile",
                    (child_text(dep, "optional") or "false") == "true",
                ))
    packaging = child_text(root, "packaging") or "jar"
    return properties, dependencies, packaging


def fetch_artifact(group, artifact, version, packaging):
    ext = "aar" if packaging == "aar" else "jar"
    filename = "{}-{}.{}".format(artifact, version, ext)
    dest = os.path.join(LIBS_DIR, filename)
    if os.path.exists(dest):
        return dest
    for repo in REPOSITORIES:
        url = "{}/{}".format(repo, repo_path(group, artifact, version, filename))
        if download(url, dest):
            return dest
    return None


def main():
    chosen = {}
    resolved = []
    queue = deque(ROOT_ARTIFACTS)
    for group, artifact, version in ROOT_ARTIFACTS:
        chosen[(group, artifact)] = version

    while queue:
        group, artifact, version = queue.popleft()
        _, dependencies, packaging = parse_pom(group, artifact, version)
        resolved.append((group, artifact, version, packaging))
        print("{}:{}:{} [{}] deps={}".format(group, artifact, version, packaging, len(dependencies)))
        for dg, da, dv, _typ, scope, optional in dependencies:
            if optional or scope in ("test", "provided", "system", "import"):
                continue
            if (dg, da) in EXCLUDED or (dg, da) in chosen:
                continue
            chosen[(dg, da)] = dv
            queue.append((dg, da, dv))

    print("\n=== downloading artifacts ===")
    ok = []
    missing = []
    for group, artifact, version, packaging in resolved:
        path = fetch_artifact(group, artifact, version, packaging)
        if path is None:
            # packaging の申告と実物が違う場合に備えて逆の拡張子も試す
            alt = "jar" if packaging == "aar" else "aar"
            path = fetch_artifact(group, artifact, version, alt)
        if path:
            ok.append(os.path.basename(path))
        else:
            missing.append("{}:{}:{} ({})".format(group, artifact, version, packaging))

    print("\nOK {}:".format(len(ok)))
    for name in ok:
        print("  " + name)
    print("\nMISSING {}:".format(len(missing)))
    for name in missing:
        print("  " + name)

    # 1 件でも欠けたら不完全な artifacts.txt を残さず異常終了する
    # (build-apk.sh は artifacts.txt の有無で再解決を判断するため)
    if missing:
        print(
            "\nERROR: {} artifact(s) could not be downloaded; "
            "libs/artifacts.txt was NOT written".format(len(missing)),
            file=sys.stderr,
        )
        sys.exit(1)

    with open(os.path.join(LIBS_DIR, "artifacts.txt"), "w") as fh:
        for name in ok:
            fh.write(name + "\n")
    print("\nwrote libs/artifacts.txt ({} files)".format(len(ok)))


if __name__ == "__main__":
    main()
