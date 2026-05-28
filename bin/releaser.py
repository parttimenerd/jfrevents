#! /usr/bin/python3

"""
This is the script to pull JDK sources, run a benchmark and combine this to produce the extended metadata files.

It has no dependencies, only requiring Python 3.6+ to be installed.
"""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Union, Tuple, Set, Optional
from urllib import request
from urllib.error import HTTPError

HELP = """
Usage:
    python3 releaser.py <command> ... <command> [--force]

Commands:
    versions          print all available JDK versions
    tags              print the current tag for all JDK versions
    download_urls     print the latest source code download URLs for every JDK version
    download          download the latest source code for every JDK version
    build_parser      build the parser JAR
    list_gc_options   print all available GC options for the current JDK
    build_jfc         create the JFC configuration file (call once before parallel create_jfr)
    create_jfr        create the JFR file for every available GC (or single GC if GC env var set)
    build_versions    build the extended metadata file for every available JDK version
    build             build the JAR with the extended metadata files
    deploy_mvn        deploy the JARs to Maven
    deploy_gh         deploy the JARs and XML files to GitHub
    deploy            the two above
    deploy_release    deploy the JARs and XML files to GitHub and Maven as releases
    all               download, build_parser, ..., deploy_gh
    clear             clear some folders

Options:
    --force           forces all forceable commands to execute

Commands "all", "create_jfr", "build_versions" can be forced
by appending "=force" to them, e.g. "all=force".

Environment variables:
    LOG               set to "true" to print more information
    GC                if set, create_jfr will only create JFR file for this GC option
"""

CURRENT_DIR = os.path.abspath(
    os.path.dirname(os.path.dirname(os.path.realpath(__file__))))

MIN_JAVA_VERSION = 21 # minimum Java version to use for building and running
CACHE_DIR = f"{CURRENT_DIR}/.cache"
CACHE_TIME = 60 * 60 * 24  # one day
RENAISSANCE_JAR = f"{CACHE_DIR}/renaissance.jar"
JDK_ZIP_DIR = f"{CACHE_DIR}/zip"
JFR_FOLDER = f"{CURRENT_DIR}/jfr"
METADATA_FOLDER = f"{CURRENT_DIR}/metadata"
ADDITIONAL_METADATA = f"{CURRENT_DIR}/additional.xml"
RESOURCES_FOLDER = f"{CURRENT_DIR}/src/main/resources/metadata"
JFC_FILE = f"{CACHE_DIR}/jfc.jfc"
GRAAL_REPO = "oracle/graal"
TOKEN_FILE = f"{CURRENT_DIR}/.github.token"

VERSION = "0.7"

VERSIONS_WITH_AI_DESCRIPTIONS = [21]

os.makedirs(JDK_ZIP_DIR, exist_ok=True)
os.makedirs(JFR_FOLDER, exist_ok=True)
os.makedirs(METADATA_FOLDER, exist_ok=True)

LOG = os.getenv("LOG", "false") == "true"

def get_java_version() -> str:
    return subprocess.check_output(
        f"java -version 2>&1 | head -n 1 | cut -d '\"' -f 2",
        shell=True).decode("utf-8").strip()

assert int(get_java_version().split(".")[0]) >= MIN_JAVA_VERSION, \
    f"Java version >= {MIN_JAVA_VERSION} is required to run this script, but found {get_java_version()}"

def log(msg: str):
    if LOG:
        print(msg)


def get_github_token() -> Optional[str]:
    if not os.path.exists(TOKEN_FILE):
        return None
    with open(TOKEN_FILE) as f:
        return f.read().strip()


def execute(args: Union[List[str], str]):
    log(f"Execute: {args}")
    try:
        subprocess.check_call(args, cwd=CURRENT_DIR, shell=isinstance(args, str),
                              stdout=sys.stdout if LOG else subprocess.DEVNULL,
                              stderr=sys.stderr if LOG else subprocess.DEVNULL)
    except subprocess.CalledProcessError as e:
        # If LOG is disabled and command failed, re-run with output to show error details
        if not LOG:
            print(f"Command failed: {args}", file=sys.stderr)
            print(f"Re-running with verbose output to show error details...", file=sys.stderr)
            print("=" * 80, file=sys.stderr)
            try:
                subprocess.check_call(args, cwd=CURRENT_DIR, shell=isinstance(args, str),
                                      stdout=sys.stdout, stderr=sys.stderr)
            except subprocess.CalledProcessError:
                pass  # We expect it to fail again, but now we have output
            print("=" * 80, file=sys.stderr)
        raise


def download_file(url, path: str, retention: int = CACHE_TIME) -> str:
    if not os.path.exists(CACHE_DIR):
        os.makedirs(CACHE_DIR)

    cache_path = f"{CACHE_DIR}/{path}" if ".cache" not in path else path

    if not os.path.exists(cache_path) or os.path.getmtime(
            cache_path) + retention <= time.time():
        print(f"Download {url} to {path}")
        headers = {}
        token = get_github_token()
        if token:
            headers["Authorization"] = f"Bearer {token}"
        req = request.Request(url, headers=headers)
        try:
            with request.urlopen(req) as response, open(cache_path, 'wb') as out_file:
                shutil.copyfileobj(response, out_file)
        except ex:
            if isinstance(ex, HTTPError) and ex.code == 401:
                print(
                    f"Got a HTTP error {ex.code}, please check your GitHub token, it might not be valid")
    return cache_path


GRAAL_JFR_DOCS_URL = f"https://raw.githubusercontent.com/{GRAAL_REPO}/master/docs/reference-manual/native-image/JFR.md"
GRAAL_SUPPORTED_EVENTS_CACHE = f"{CACHE_DIR}/graal_supported_events.json"
# Refresh weekly — the list rarely changes between GraalVM releases
GRAAL_EVENTS_CACHE_TIME = 60 * 60 * 24 * 7


def fetch_graal_supported_events() -> List[str]:
    """Fetch the list of Java-level JDK JFR events supported by GraalVM Native Image
    by parsing the oracle/graal JFR.md documentation."""
    md_path = download_file(GRAAL_JFR_DOCS_URL, "graal_jfr.md", retention=GRAAL_EVENTS_CACHE_TIME)
    events = []
    with open(md_path) as f:
        for line in f:
            # Match table cells containing jdk.EventName (with optional footnote markers like [^1])
            for word in line.split():
                word = word.strip("|` \t")
                if word.startswith("jdk.") and word.replace("jdk.", "").isidentifier():
                    events.append(word[len("jdk."):])
    return list(dict.fromkeys(events))  # deduplicate, preserve order


def download_json(url, path: str) -> Any:
    """ Download the JSON file from the given URL and save it to the given path, return the JSON object """
    with open(download_file(url, path)) as f:
        return json.load(f)


def download_zip(url, path: str, retention: int = CACHE_TIME, max_tries: int = 3) -> str:
    """ Download the ZIP file from the URL and save it to the given path, return the path to unpacked directory """
    assert path.endswith(".zip")
    path = download_file(url, path, retention=retention * 100)
    dir_path = path[:-4]
    if not os.path.exists(dir_path):
        log("Unzipping " + path)
        try:
            execute(
            ["unzip", "-q", "-o", path, "-d", dir_path])
        except subprocess.CalledProcessError:
            if max_tries == 0:
                print(f"Could not unzip {path}")
                raise
            log("retry downloading and unzipping " + path)
            # if the unzip fails, we try to redownload the file and try again
            shutil.rmtree(dir_path, ignore_errors=True)
            os.remove(path)
            return download_zip(url, path, retention=retention, max_tries=max_tries - 1)
    return dir_path


@dataclass
class Repo:
    version: int
    name: str
    url: str
    permanent_url: str
    graal_version: Optional["GraalVersion"] = None
    """ GitHub url of the main folder """


def get_repos() -> List[Repo]:
    """ Get all async-profiler versions """
    json = []
    for i in range(2):
        json += download_json(
            f"https://api.github.com/orgs/openjdk/repos?per_page=100&page={i + 1}",
            f"repos{i}.json")
    repos = []

    def create_repo(version: int, name: str, repo_url: str) -> Repo:
        r = Repo(version, name, repo_url, repo_url)
        latest = get_latest_release_name_and_zip_url(r)
        return Repo(version, name, repo_url + "/tree/" + latest.name.replace("+", "%2B"),
                    repo_url + "/tree/" + latest.commit,
                    get_graal_version(version))

    for repo in json:
        name = repo["name"]
        if name.startswith("jdk") and name.endswith("u"):
            version = int(name[3:][:-1])
            if version >= 11:
                repos.append(create_repo(version, name, repo["html_url"]))
    max_version = max(repos, key=lambda r: r.version).version
    repos.append(create_repo(max_version + 1, "jdk",
                      "https://github.com/openjdk/jdk"))
    return sorted(repos, key=lambda r: r.version)


def get_tags(repo: Repo) -> List[Dict[str, Any]]:
    return [d for d in download_json(
        f"https://api.github.com/repos/openjdk/{repo.name}/tags?per_page=1000",
        f"tags_{repo.name}.json") if
            d["name"].startswith(f"jdk-{repo.version}")]

@dataclass
class LatestReleaseInfo:
    name: str
    zip_url: str
    commit: str

def get_latest_release_name_and_zip_url(repo: Repo) -> LatestReleaseInfo:
    names = [d["name"] for d in get_tags(repo)]
    latest_name = names[0]
    if any("." in name for name in names):
        latest_name = [name for name in names if "." in name][0]
    d = [d for d in get_tags(repo) if d["name"] == latest_name][0]
    return LatestReleaseInfo(latest_name, d["zipball_url"], d["commit"]["sha"])


def download_urls():
    for repo in get_repos():
        latest = get_latest_release_name_and_zip_url(repo)
        print(f"{repo.version}: {latest.zip_url}")
    for v in get_graal_versions():
        print(f"{v.jdk_version}: {v.zip_url}")


def download_latest_release(repo: Repo):
    """ Download the latest release for the given repo and graal """
    latest = get_latest_release_name_and_zip_url(repo)
    path = download_zip(latest.zip_url, f"{JDK_ZIP_DIR}/{repo.name}_{latest.name}.zip",
                        retention=CACHE_TIME * 10)
    result_link = f"{CACHE_DIR}/{repo.name}"
    try:
        os.remove(result_link)
    except FileNotFoundError:
        pass
    assert not os.path.exists(result_link)
    src = next(Path(path).glob("*"))
    os.symlink(src, result_link)


@dataclass
class GraalVersion:
    tag: str
    jdk_version: int
    graal_version: str
    url: str
    """ GitHub url of the main folder """
    zip_url: str


def get_graal_vm_version_from_tag(tag: str) -> str:
    # look into https://github.com/oracle/graal/blob/$tag/compiler/mx.compiler/suite.py
    # for the version
    # look for first "  "version" : "23.1.1","
    # and return the version
    with open(download_file(f"https://raw.githubusercontent.com/{GRAAL_REPO}/{tag}/compiler/mx.compiler/suite.py", f"graal_{tag}_suite.py")) as f:
        for line in f.readlines():
            if '"version"' in line:
                return line.replace("'", '"').split('"')[3]
    assert False, "Could not find version"


def get_graal_versions() -> List[GraalVersion]:
    # download tags paged from https://api.github.com/repos/{GRAAL_REPO}/tags
    # and combine json lists

    tags = []
    for page in range(5):
        raw = download_json(f"https://api.github.com/repos/{GRAAL_REPO}/tags?per_page=100&page={page + 1}",
                            f"tags_graal_{page}.json")
        tags += raw
        if not raw:
            break

    # tag format of considered tags "jdk-<version>.<rest>"
    # use for every version the latest tag and return the GraalVersion object
    versions = {}
    for tag in tags:
        if tag["name"].startswith("jdk-"):
            version_number = tag["name"][4:].replace("+", ".").split(".")[0]
            version = int(version_number)
            if version in versions:
                continue
            main_url: str = f"https://github.com/oracle/graal/blob/{tag['name']}"
            vm_version = get_graal_vm_version_from_tag(tag["name"])
            versions[version] = GraalVersion(tag["name"], version, vm_version,
                                             main_url, tag["zipball_url"])
    return sorted(versions.values(), key=lambda v: v.jdk_version)


def get_graal_version(version: int) -> Optional[GraalVersion]:
    return ([v for v in get_graal_versions() if v.jdk_version == version] + [None])[0]


def has_graal_version(version: int) -> bool:
    return get_graal_version(version) is not None


def download_graal_version(version: GraalVersion) -> Path:
    """ Download and unpack the graal version's zip file """
    path = download_zip(version.zip_url, f"{JDK_ZIP_DIR}/graal_{version.tag}.zip",
                        retention=CACHE_TIME * 10)
    result_link = graal_folder(version)
    if os.path.exists(result_link):
        os.unlink(result_link)
    os.symlink(next(Path(path).glob("*")), result_link)
    return Path(result_link)



def download_benchmarks():
    download_file("https://github.com/renaissance-benchmarks"
                  "/renaissance/releases/download/v0.15.0/renaissance-gpl-0.15.0.jar",
                  RENAISSANCE_JAR)


def repo_folder(repo: Repo) -> str:
    return f"{CACHE_DIR}/{repo.name}"


def graal_folder(version: GraalVersion) -> str:
    return f"{CACHE_DIR}/graal_{version.tag}"


def download_repo_if_not_exists(repo: Repo):
    """ Download the latest release for the given repo if it does not exist """
    if not os.path.exists(repo_folder(repo)):
        download_latest_release(repo)
    if has_graal_version(repo.version):
        graal_version = get_graal_version(repo.version)
        if not graal_folder(graal_version):
            download_graal_version(graal_version)


def download(force: bool = False):
    """ Download the latest release for every version """
    for repo in get_repos():
        download_latest_release(repo)
        graal = get_graal_version(repo.version)
        if graal:
            download_graal_version(graal)
    download_benchmarks()


def list_jdk_versions() -> str:
    """ List all JDK versions as a space-separated string for CI matrix """
    repos = get_repos()
    return " ".join(str(repo.version) for repo in repos)


def download_jdk_version(version: int):
    """ Download JDK sources for a specific version """
    repos = get_repos()
    repo = [r for r in repos if r.version == version]
    if not repo:
        print(f"ERROR: JDK version {version} not found")
        sys.exit(1)

    repo = repo[0]
    print(f"Downloading JDK {version}...")
    download_latest_release(repo)

    graal = get_graal_version(repo.version)
    if graal:
        print(f"Downloading Graal for JDK {version}...")
        download_graal_version(graal)


def build_parser():
    execute("mvn clean package assembly:single")


def get_parser_or_build() -> str:
    """ Get the path to the parser JAR, build it if it doesn't exist """
    parser_jar = f"{CURRENT_DIR}/target/jfreventcollector-full.jar"
    if not os.path.exists(parser_jar):
        build_parser()
    assert os.path.exists(parser_jar)
    return parser_jar


GC_OPTIONS = []


def get_platform() -> str:
    """Return a short lowercase platform identifier.
    In CI, set the PLATFORM env var to ${{ runner.os }} (Linux, macOS, Windows).
    Locally falls back to sys.platform."""
    env = os.getenv("PLATFORM")
    if env:
        return env.lower()
    import sys as _sys
    p = _sys.platform
    if p.startswith("darwin"):
        return "macos"
    if p.startswith("win"):
        return "windows"
    return "linux"


def jfr_file_name(gc_options: str, platform: str = None) -> str:
    p = platform or get_platform()
    return f"{JFR_FOLDER}/sample_{p}_{gc_options}.jfr"


def jfr_sample_app_file_name(gc_options: str, platform: str = None) -> str:
    """JFR file produced by the sample app run alongside renaissance."""
    p = platform or get_platform()
    return f"{JFR_FOLDER}/sample_{p}_{gc_options}_events.jfr"


def run_sample_app_jfr(gc_option: str, platform: str = None):
    """Run the jfr_sample app with JFR to produce additional event coverage."""
    if not os.path.exists(GRAALVM_SAMPLE_APP_SRC):
        return
    out_dir = GRAALVM_SAMPLE_APP_OUT
    app_jar = GRAALVM_SAMPLE_APP_JAR
    jfr_file = jfr_sample_app_file_name(gc_option, platform)
    os.makedirs(out_dir, exist_ok=True)
    if not os.path.exists(app_jar):
        execute(f"javac -d {out_dir} {GRAALVM_SAMPLE_APP_SRC}")
        execute(f"jar -cfe {app_jar} jfr_sample.Main -C {out_dir} .")
    execute(["java",
             f"-XX:StartFlightRecording=filename={jfr_file},settings={JFC_FILE},duration=20s",
             "-XX:+" + gc_option, "-jar", app_jar])


def list_gc_options() -> List[str]:
    """ List all GC options for the current JDK """
    global GC_OPTIONS
    if not GC_OPTIONS:
        result = subprocess.check_output(
            ["java", "-XX:+PrintFlagsFinal", "-version"],
            stderr=subprocess.STDOUT).decode("utf-8")
        GC_OPTIONS = [line.strip().split(" ")[1] for line in result.splitlines()
                      if
                      " Use" in line and "GC " in line and "Adaptive" not in line and "Maximum" not in line]
    return GC_OPTIONS


def create_jfc():
    """ Create a maximalist JFC file: all events enabled, thresholds zeroed, level=all """
    import re as _re
    print("Creating JFC configuration file...")
    with open(os.getenv("JAVA_HOME") + "/lib/jfr/profile.jfc") as f:
        content = f.read()
    # Enable all disabled events
    content = content.replace(">false<", ">true<")
    # Zero all thresholds so short-duration events are captured
    content = _re.sub(
        r'(<setting name="threshold"[^>]*>)[^<]+(<)',
        r'\g<1>0 ms\2',
        content)
    # Use level=all for DeprecatedInvocation to capture all deprecated calls (not just forRemoval)
    content = _re.sub(
        r'(name="jdk\.DeprecatedInvocation".*?<setting name="level">)[^<]+(</setting>)',
        r'\g<1>all\2',
        content, flags=_re.DOTALL)
    with open(JFC_FILE, "w") as f2:
        f2.write(content)
    print(f"✅ JFC file created at {JFC_FILE}")


def build_jfc():
    """ Wrapper for create_jfc to be called from CLI """
    create_jfc()
    if not os.path.exists(RENAISSANCE_JAR):
        download_benchmarks()


def create_jfr(gc_option: str = None, force: bool = False):
    # Create JFC if it doesn't exist (for non-CI usage)
    if not os.path.exists(JFC_FILE):
        create_jfc()
    if not os.path.exists(RENAISSANCE_JAR):
        download_benchmarks()

    # Check for GC environment variable
    gc_env = os.getenv("GC")
    if gc_env and not gc_option:
        gc_option = gc_env
        print(f"Using GC option from environment: {gc_option}")

    jfr_file = jfr_file_name(gc_option)
    if os.path.exists(jfr_file) and not force and os.path.getmtime(
            jfr_file) > os.path.getmtime(JFC_FILE):
        print(f"JFR file {jfr_file} already exists and is up to date")
        return
    if gc_option:
        # Check that the GC flag is supported before running the full benchmark
        check = subprocess.run(["java", "-XX:+" + gc_option, "-version"],
                               capture_output=True)
        if check.returncode != 0:
            print(f"Skipping {gc_option}: not supported on this JVM/platform "
                  f"({check.stderr.decode('utf-8', errors='replace').splitlines()[0] if check.stderr else 'unknown error'})",
                  file=sys.stderr)
            return
        print(f"Creating JFR file for GC option {gc_option}")
        try:
            execute(["java",
                     f"-XX:StartFlightRecording=filename={jfr_file},settings={JFC_FILE}",
                     "-XX:+" + gc_option, "-jar", RENAISSANCE_JAR, "-t", "5",
                     "-r", "1", "all"])
        except subprocess.CalledProcessError as ex:
            if not os.path.exists(jfr_file_name(gc_option)):
                raise ex
            print(f"Caught a Java error", file=sys.stderr)
        # Also run the sample app to capture additional event types not in renaissance
        run_sample_app_jfr(gc_option)
    else:
        print(
            f"Creating JFR file for GC options: {', '.join(list_gc_options())}")
        for gc_option in list_gc_options():
            create_jfr(gc_option)
    os.system(f"rm -fr '{CURRENT_DIR}/harness*'")


def create_jfr_if_needed(gc_option: str = None):
    if gc_option:
        jfr_file = jfr_file_name(gc_option)
        if not os.path.exists(jfr_file) or os.path.getsize(jfr_file) == 0:
            create_jfr(gc_option)
    else:
        for gc_option in list_gc_options():
            create_jfr_if_needed(gc_option)


def meta_file_name(repo: Repo, wo_examples: bool = False,
                   wo_ai_descriptions: bool = False,
                   only_events: bool = False) -> str:
    return (f"{METADATA_FOLDER}/metadata_{repo.version}"
            f"{'_wo_examples' if wo_examples else ''}"
            f"{'_events' if only_events else ''}"
            f"{'wo_ai_descriptions' if wo_ai_descriptions else ''}.xml")


def add_events(repo: Repo):
    metadata_file = meta_file_name(repo)
    execute(
        f"java -cp {get_parser_or_build()} me.bechberger.collector.EventAdderKt {metadata_file} {repo_folder(repo)} "
        f"{repo.url} {repo.permanent_url} {metadata_file}")


def add_graal_events(repo: Repo):
    metadata_file = meta_file_name(repo)
    graal_version = get_graal_version(repo.version)
    execute(
        f"java -cp {get_parser_or_build()} me.bechberger.collector.GraalEventAdderKt {metadata_file} {graal_folder(graal_version)} "
        f"{graal_version.url} {graal_version.graal_version} {graal_version.tag} {metadata_file}")
    add_java_level_graal_events(repo)


def add_java_level_graal_events(repo: Repo):
    """Mark Java-level JDK JFR events supported by GraalVM Native Image using the oracle/graal JFR.md docs."""
    metadata_file = meta_file_name(repo)
    events = fetch_graal_supported_events()
    if not events:
        print("Warning: no Java-level Graal events fetched from JFR.md — skipping")
        return
    events_args = " ".join(events)
    execute(
        f"java -cp {get_parser_or_build()} me.bechberger.collector.MarkJavaLevelGraalEventsKt "
        f"{metadata_file} {events_args} {metadata_file}")

def java_version() -> str:
    return subprocess.check_output(
        f"java -version 2>&1 | head -n 1 | cut -d '\"' -f 2",
        shell=True).decode("utf-8")


def add_examples(repo: Repo):
    metadata_file = meta_file_name(repo)
    platform = get_platform()
    for gc_option in list_gc_options():
        gc = gc_option[3:]
        label = f"{platform}_{gc}"
        description = f"Run of renaissance benchmark with {gc} on Java {java_version().strip()} ({platform})"
        jfr_file = jfr_file_name(gc_option, platform)
        if not os.path.exists(jfr_file):
            print(f"Skipping {label}: JFR file not found: {jfr_file}")
            continue
        # Include the sample app JFR if present (provides additional event coverage)
        sample_file = jfr_sample_app_file_name(gc_option, platform)
        files_arg = f"{jfr_file},{sample_file}" if os.path.exists(sample_file) else jfr_file
        execute(
            f"java -cp {get_parser_or_build()} me.bechberger.collector.ExampleAdderKt {metadata_file} "
            f"--platform={platform} {label} \"{description}\" {files_arg} {metadata_file}")


GRAALVM_SAMPLE_APP_SRC = f"{CURRENT_DIR}/src/main/java/jfr_sample/Main.java"
GRAALVM_SAMPLE_APP_OUT = f"{CURRENT_DIR}/.cache/jfr_sample_out"
GRAALVM_SAMPLE_APP_JAR = f"{CURRENT_DIR}/.cache/jfr_sample_app.jar"
GRAALVM_SAMPLE_APP_BIN = f"{CURRENT_DIR}/jfr_sample_app"


def graalvm_jfr_file_name(mode: str, gc: str = "native") -> str:
    p = get_platform()
    suffix = gc if mode == "jvm" else "native"
    return f"{JFR_FOLDER}/sample_{p}_graalvm_{suffix}.jfr"


def create_jfr_graalvm_native():
    """Compile the tiny JFR sample app to native image and run it with JFR enabled."""
    if not os.path.exists(JFC_FILE):
        create_jfc()
    jfr_file = graalvm_jfr_file_name("native")
    os.makedirs(GRAALVM_SAMPLE_APP_OUT, exist_ok=True)
    execute(f"javac -d {GRAALVM_SAMPLE_APP_OUT} {GRAALVM_SAMPLE_APP_SRC}")
    execute(f"jar -cfe {GRAALVM_SAMPLE_APP_JAR} jfr_sample.Main -C {GRAALVM_SAMPLE_APP_OUT} .")
    execute(f"native-image --enable-monitoring=jfr -jar {GRAALVM_SAMPLE_APP_JAR} "
            f"{GRAALVM_SAMPLE_APP_BIN}")
    execute([GRAALVM_SAMPLE_APP_BIN,
             f"-XX:StartFlightRecording=filename={jfr_file},settings={JFC_FILE},duration=18s"])


def create_jfr_graal(force: bool = False):
    """Create JFR samples using GraalVM. MODE env var selects 'jvm' (default) or 'native'."""
    mode = os.getenv("MODE", "jvm")
    if mode == "native":
        create_jfr_graalvm_native()
    else:
        if not os.path.exists(JFC_FILE):
            create_jfc()
        if not os.path.exists(RENAISSANCE_JAR):
            download_benchmarks()
        gc_option = "UseG1GC"
        jfr_file = graalvm_jfr_file_name("jvm", "G1GC")
        if os.path.exists(jfr_file) and not force:
            print(f"GraalVM JVM JFR file already exists: {jfr_file}")
            return
        execute(["java",
                 f"-XX:StartFlightRecording=filename={jfr_file},settings={JFC_FILE}",
                 "-XX:+" + gc_option, "-jar", RENAISSANCE_JAR, "-t", "5", "-r", "1", "all"])


def add_graal_examples(repo: Repo):
    """Add GraalVM JFR sample examples to the metadata."""
    import glob as _glob
    metadata_file = meta_file_name(repo)
    graal_files = sorted(_glob.glob(f"{JFR_FOLDER}/sample_*_graalvm_*.jfr"))
    if not graal_files:
        print("No GraalVM JFR files found, skipping graal examples")
        return
    for f in graal_files:
        base = os.path.basename(f)[len("sample_"):-len(".jfr")]  # e.g. linux_graalvm_G1GC
        parts = base.split("_graalvm_")
        plat, suffix = parts[0], parts[1]  # linux, G1GC or native
        label = f"graalvm_{plat}_{suffix}"
        if suffix == "native":
            desc = f"GraalVM Native Image JFR recording ({plat})"
            platform_tag = "graalvm-native"
        else:
            desc = f"GraalVM JVM with {suffix} on {plat}"
            platform_tag = "graalvm"
        execute(
            f"java -cp {get_parser_or_build()} me.bechberger.collector.ExampleAdderKt "
            f"{metadata_file} --platform={platform_tag} {label} \"{desc}\" {f} {metadata_file}")


def add_additional_descriptions(repo: Repo):
    log(f"Add additional descriptions for version {repo.version} via AI")
    execute(
        f"java -cp {get_parser_or_build()} me.bechberger.collector.AdditionalDescriptionAdderKt {metadata_file} "
        f"{ADDITIONAL_METADATA} {metadata_file}")


def add_ai_generated_descriptions(repo: Repo):
    if (not os.path.exists(CURRENT_DIR + "/.openai.key") or
            repo.version not in VERSIONS_WITH_AI_DESCRIPTIONS):
        return
    metadata_file = meta_file_name(repo)
    log(f"Add AI generated descriptions for version {repo.version}")
    execute(f"java -cp {get_parser_or_build()} "
            "me.bechberger.collector.AIDescriptionAdderKt "
            f"{metadata_file} "
            f"{repo_folder(repo)} "
            f"{metadata_file}")


def add_source_code_context(repo: Repo):
    metadata_file = meta_file_name(repo)
    log(f"Add source code context for version {repo.version}")
    execute(f"java -cp {get_parser_or_build()} "
            "me.bechberger.collector.SourceCodeContextAdderKt "
            f"{metadata_file} "
            f"{repo_folder(repo)} "
            f"{metadata_file}")


def build_version(repo: Repo, force: bool = False):
    create_jfr_if_needed()
    download_repo_if_not_exists(repo)
    meta_file = meta_file_name(repo)
    meta_wo_examples = meta_file_name(repo, wo_examples=True)
    # copy metadata — consider all JFR files present regardless of platform
    import glob as _glob
    all_jfr_files = _glob.glob(f"{JFR_FOLDER}/sample_*.jfr")
    generated_example_min_mtime = min(
        (os.path.getmtime(f) for f in all_jfr_files),
        default=0
    )
    if os.path.exists(meta_file):
        meta_file_mtime = os.path.getmtime(meta_file)
        if not force and meta_file_mtime > \
                max(os.path.getmtime(repo_folder(repo) + "/src"),
                    generated_example_min_mtime):
            print(f"Metadata file {meta_file} already exists and is up to date")
            execute(f"cp {meta_file} {meta_wo_examples}")
            return
        os.remove(meta_file)
    execute(
        f"cp \"{repo_folder(repo)}/src/hotspot/share/jfr/metadata/metadata.xml\" {meta_file}")
    print(f"Add events from JDK source code for version {repo.version}")
    add_events(repo)
    shutil.copy(meta_file, meta_file_name(repo, only_events=True))
    if has_graal_version(repo.version):
        print(f"Add events from Graal source code for version {repo.version}")
        add_graal_events(repo)
    print(f"Add additional descriptions for version {repo.version}")
    add_additional_descriptions(repo)
    if os.path.exists(meta_wo_examples):
        os.remove(meta_wo_examples)
    execute(f"cp {meta_file} {meta_wo_examples}")
    print(f"Add examples from JFR files for version {repo.version}")
    add_examples(repo)
    print(f"Add GraalVM examples from JFR files for version {repo.version}")
    add_graal_examples(repo)
    print(f"Add source code context for version {repo.version}")
    add_source_code_context(repo)
    add_ai_generated_descriptions(repo)


def build_versions(force: bool = False):
    for repo in get_repos():
        build_version(repo, force=force)
    print("Add since and until")
    args = ' '.join(
        f"{repo.version} \"{meta_file_name(repo)}\" \"{meta_file_name(repo)}\""
        for repo in get_repos())
    execute(
        f"java -cp {get_parser_or_build()} me.bechberger.collector.SinceAdderKt {args}")


def build():
    """ Build the wrappers for the given release """
    if os.path.exists(RESOURCES_FOLDER):
        shutil.rmtree(RESOURCES_FOLDER)
    print("Build package")
    execute(f"mvn clean package assembly:single")
    os.makedirs(RESOURCES_FOLDER, exist_ok=True)
    for repo in get_repos():
        if not os.path.exists(meta_file_name(repo)):
            build_version(repo)
        shutil.copy(meta_file_name(repo),
                    f"{RESOURCES_FOLDER}/metadata_{repo.version}.xml")
    with open(RESOURCES_FOLDER + "/versions", "w") as f:
        f.write("\n".join(str(repo.version) for repo in get_repos()))
    with open(RESOURCES_FOLDER + "/specific_versions", "w") as f:
        f.write("\n".join(
            f"{repo.version}: {get_latest_release_name_and_zip_url(repo).name}"
            for repo in get_repos()))
    with open(RESOURCES_FOLDER + "/time", "w") as f:
        f.write(str(int(time.time())))
    print("Build loader package")
    execute(f"mvn -f pom_loader.xml package assembly:single")


def clear_harness_and_launchers():
    """ Delete all harness and launcher JARs """
    execute(f"rm -rf '{CURRENT_DIR}/harness*'")
    execute(f"rm -rf '{CURRENT_DIR}/launcher*'")


def clear():
    clear_harness_and_launchers()
    shutil.rmtree(CACHE_DIR, ignore_errors=True)
    shutil.rmtree(JFR_FOLDER, ignore_errors=True)
    shutil.rmtree(METADATA_FOLDER, ignore_errors=True)
    shutil.rmtree(f"{CURRENT_DIR}/target", ignore_errors=True)
    shutil.rmtree(RESOURCES_FOLDER, ignore_errors=True)


def get_changelog() -> str:
    return \
    Path(f"{CURRENT_DIR}/CHANGELOG.md").read_text().split("##")[1].split(
        "\n\n")[0].strip()


def deploy_github():
    changelog = get_changelog()
    title = f"Release {VERSION}"
    print(f"Deploy to GitHub")
    if not os.path.exists(f"{CURRENT_DIR}/target/jfreventcollector-full.jar"):
        build()
    with tempfile.TemporaryDirectory() as d:
        changelog_file = f"{d}/CHANGELOG.md"
        with open(changelog_file, "w") as of:
            of.write(changelog)
            of.close()

        paths = []

        def copy(file: str, name: str):
            shutil.copy(f"{CURRENT_DIR}/{file}", f"{d}/{name}")
            paths.append(f"{d}/{name}")

        copy("target/jfreventcollector-full.jar", "jfreventcollector.jar")
        copy("target/jfreventcollection-full.jar", "jfreventcollection.jar")
        for repo in get_repos():
            copy(f"metadata/metadata_{repo.version}.xml",
                 f"metadata_{repo.version}.xml")
            copy(f"metadata/metadata_{repo.version}_wo_examples.xml",
                 f"metadata_{repo.version}_wo_examples.xml")

        flags_str = f"-F {changelog_file} -t '{title}' --latest"
        paths_str = " ".join(f'"{p}"' for p in paths)
        cmd = f"gh release create {VERSION} {flags_str} {paths_str}"
        try:
            subprocess.check_call(cmd, shell=True, cwd=CURRENT_DIR,
                                  stdout=subprocess.DEVNULL,
                                  stderr=subprocess.DEVNULL)
        except subprocess.CalledProcessError:
            # this is either a real problem or it means that the release already exists
            # in the latter case, we can just update it
            cmd = f"gh release edit {VERSION} {flags_str}; gh release upload {VERSION} {paths_str} --clobber"
            try:
                subprocess.check_call(cmd, shell=True, cwd=CURRENT_DIR,
                                      stdout=subprocess.DEVNULL,
                                      stderr=subprocess.DEVNULL)
            except subprocess.CalledProcessError:
                os.system(
                    f"cd {CURRENT_DIR}; {cmd}")


def deploy_maven(snapshot: bool = True):
    print(f"Deploy{' snapshot' if snapshot else ''}")
    for suffix in ["", "_loader"]:
        pom = f"pom{suffix}.xml"
        cmd = f"mvn " \
              f"-Dproject.suffix='{'-SNAPSHOT' if snapshot else ''}' -Dproject.vversion={VERSION} -f {pom} clean deploy"
        print(f"Deploy maven: {cmd}")
        try:
            subprocess.check_call(cmd, shell=True, cwd=CURRENT_DIR,
                                  stdout=subprocess.PIPE,
                                  stderr=subprocess.PIPE)
        except subprocess.CalledProcessError:
            os.system(
                f"cd {CURRENT_DIR}; {cmd}")
            raise


def deploy(snapshot: bool = True):
    deploy_maven(snapshot)
    deploy_github()


@dataclass
class CLIArgs:
    commands: List[str]
    forced_commands: List[str]


def parse_cli_args() -> CLIArgs:
    available_commands = ["versions", "download_urls", "download", "list_jdk_versions", "download_jdk_version",
                          "build_parser", "list_gc_options", "build_jfc", "create_jfr", "create_jfr_graal",
                          "build_versions",
                          "build", "deploy_mvn", "deploy_gh", "deploy",
                          "deploy_release", "clear", "all", "tags"]
    forcable_commands = ["all", "create_jfr", "create_jfr_graal", "build_versions"]
    commands = []
    forced_commands = []
    jdk_version_arg = None

    for i, arg in enumerate(sys.argv[1:]):
        if arg == "--force":
            forced_commands = forcable_commands
        elif arg.startswith("--jdk-version="):
            jdk_version_arg = int(arg.split("=")[1])
        else:
            cmd, *args = arg.split("=")
            if cmd not in available_commands:
                print(f"Unknown command: {cmd}")
                print(HELP)
                sys.exit(1)
            commands.append(cmd)
            if len(args) == 1:
                if args[0] == "force":
                    if cmd not in forcable_commands:
                        print(f"Command {cmd} cannot be forced")
                        print(HELP)
                        sys.exit(1)
                    forced_commands.append(cmd)
                else:
                    print(f"Unknown argument: {args[0]} to command {cmd}")
                    print(HELP)
                    sys.exit(1)
            elif len(args) > 1:
                print(f"Too many arguments: {args} to command {cmd}")
                print(HELP)
                sys.exit(1)
    if not commands:
        print(HELP)
    return CLIArgs(commands, forced_commands), jdk_version_arg


def cli():
    args, jdk_version_arg = parse_cli_args()
    commands = args.commands
    forced = args.forced_commands

    coms = {
        "versions": lambda: print(
            " ".join(str(r.version) for r in get_repos())),
        "tags": lambda: print(
            "\n".join(
                f"{r.version}: {get_latest_release_name_and_zip_url(r).name}" for
                r in get_repos())),
        "download_urls": download_urls,
        "download": download,
        "list_jdk_versions": lambda: print(list_jdk_versions()),
        "download_jdk_version": lambda: download_jdk_version(jdk_version_arg if jdk_version_arg else int(os.getenv("JDK_VERSION", "0"))),
        "build_parser": build_parser,
        "list_gc_options": lambda: print(" ".join(list_gc_options())),
        "build_jfc": build_jfc,
        "create_jfr": create_jfr,
        "create_jfr_graal": create_jfr_graal,
        "build_versions": build_versions,
        "build": build,
        "deploy_mvn": lambda: deploy_maven(snapshot=True),
        "deploy_gh": deploy_github,
        "deploy": lambda: deploy(snapshot=True),
        "deploy_release": lambda: deploy(snapshot=False),
        "clear": clear,
        "all": lambda: [download(), build_parser(),
                        create_jfr(force="create_jfr" in forced),
                        build_versions(force="build_versions" in forced),
                        build(),
                        deploy(snapshot=True)]
    }
    for command in commands:
        log(f"Execute command {command}")
        if command in forced:
            coms[command](force=True)
        else:
            coms[command]()
    clear_harness_and_launchers()


if __name__ == '__main__':
    try:
        cli()
    except HTTPError as ex:
        if ex.code == 403:
            print(
                "GitHub API rate limit exceeded, please store a GitHub token "
                f"in {TOKEN_FILE} to increase the limit")
        print(f"Caught HTTP error: {ex}")
        sys.exit(1)