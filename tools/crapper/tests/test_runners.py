import json
from pathlib import Path

import pytest

from crapper.discover import is_test_file
from crapper.runners import (
    PackageJsonError,
    _clean_clojure,
    _clean_dir,
    _coverage_report,
    _ensure_python_module,
    _ensure_vitest_coverage,
    _manifest_snapshot,
    _prepare_report,
    _python_executable,
    _python_kind,
    _record_python_lcov,
    _restore_manifests,
    _rust_kind,
    _vitest_version,
    python_coverage_commands,
    python_roots,
    python_sources,
    run_coverage,
    run_shell,
    rust_coverage_command,
    rust_modules,
    typescript_command,
    typescript_packages,
    uses_pytest,
)


def _as_text(command) -> str:
    if isinstance(command, list):
        return " ".join(command)
    return command


def _write(root: Path, relative: str, source: str) -> Path:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(source, encoding="utf-8")
    return path


def test_vitest_uses_its_own_coverage_instead_of_c8(tmp_path):
    _write(
        tmp_path,
        "package.json",
        json.dumps({"scripts": {"test": "vitest run"}}),
    )
    _write(tmp_path, "vitest.config.ts", "export default {}\n")
    source = _write(tmp_path, "src/book.ts", "export function plan() { return 1 }\n")
    _write(tmp_path, "src/book.test.ts", "import { plan } from './book'\n")
    report_dir = tmp_path / "target" / "coverage" / "typescript"
    command = typescript_command(tmp_path, [source, tmp_path / "src" / "book.test.ts"], report_dir)
    assert command is not None
    text = _as_text(command)
    assert "c8" not in text
    assert "vitest run --coverage" in text
    assert "--coverage.reporter=lcov" in text
    assert f"--coverage.reportsDirectory={report_dir}" in text
    assert "--coverage.include=src/book.ts" in text
    assert "book.test.ts" not in text


def test_existing_coverage_script_is_left_alone(tmp_path):
    _write(
        tmp_path,
        "package.json",
        json.dumps({"scripts": {"coverage": "vitest run --coverage", "test": "vitest run"}}),
    )
    command = typescript_command(tmp_path, [], tmp_path / "coverage")
    assert command == ["npm", "run", "coverage"]


def test_node_test_script_still_uses_c8(tmp_path):
    _write(tmp_path, "package.json", json.dumps({"scripts": {"test": "node --test"}}))
    report_dir = tmp_path / "target" / "coverage" / "typescript"
    command = typescript_command(tmp_path, [], report_dir)
    assert command == [
        "npx",
        "--yes",
        "c8",
        "--reporter=lcov",
        "--reports-dir",
        str(report_dir),
        "npm",
        "test",
    ]


def test_typescript_package_is_found_from_a_source_file(tmp_path):
    _write(tmp_path, "web/package.json", json.dumps({"scripts": {"test": "vitest run"}}))
    source = _write(tmp_path, "web/src/app.ts", "export const n = 1\n")
    assert typescript_packages(tmp_path, [source]) == [(tmp_path / "web").resolve()]


def test_rust_module_is_the_nearest_cargo_package(tmp_path):
    _write(tmp_path, "src-tauri/Cargo.toml", '[package]\nname = "bookwriter"\nversion = "0.1.0"\n')
    lib = _write(tmp_path, "src-tauri/src/lib.rs", "pub fn read_text() {}\n")
    assert rust_modules(tmp_path, [lib]) == [(tmp_path / "src-tauri").resolve()]


def test_manifests_are_restored_after_a_coverage_install(tmp_path):
    package_json = tmp_path / "package.json"
    lock = tmp_path / "package-lock.json"
    package_json.write_bytes(b'{"name":"demo"}\n')
    lock.write_bytes(b'{"lockfileVersion":3}\n')
    snapshot = _manifest_snapshot(tmp_path)
    package_json.write_bytes(b'{"name":"changed"}\n')
    lock.unlink()
    shrink = tmp_path / "npm-shrinkwrap.json"
    shrink.write_text("{}\n", encoding="utf-8")
    _restore_manifests(snapshot)
    assert package_json.read_bytes() == b'{"name":"demo"}\n'
    assert lock.read_bytes() == b'{"lockfileVersion":3}\n'
    assert not shrink.exists()


def test_python_project_uses_pytest_and_coverage_lcov(tmp_path):
    _write(tmp_path, "pyproject.toml", "[tool.pytest.ini_options]\ntestpaths = ['tests']\n")
    source = _write(tmp_path, "src/demo/box.py", "def choose(x):\n    return x\n")
    _write(tmp_path, "tests/test_box.py", "def test_choose():\n    assert True\n")
    assert python_roots(tmp_path, [source]) == [tmp_path.resolve()]
    assert uses_pytest(tmp_path)
    assert python_sources(tmp_path, [source, tmp_path / "tests" / "test_box.py"]) == "src"
    assert is_test_file(tmp_path / "tests" / "test_box.py")
    data = tmp_path / "target" / "coverage" / "python" / ".coverage"
    report = data.parent / "lcov.info"
    run, lcov = python_coverage_commands("python3", "pytest", data, report, "src")
    assert run == [
        "python3",
        "-m",
        "coverage",
        "run",
        f"--data-file={data}",
        "--source=src",
        "-m",
        "pytest",
    ]
    assert lcov == [
        "python3",
        "-m",
        "coverage",
        "lcov",
        f"--data-file={data}",
        "-o",
        str(report),
    ]


def test_python_package_without_pytest_uses_unittest(tmp_path):
    _write(tmp_path, "setup.py", "from setuptools import setup\nsetup(name='demo')\n")
    source = _write(tmp_path, "demo/app.py", "def run():\n    return 1\n")
    assert not uses_pytest(tmp_path)
    run, _lcov = python_coverage_commands("python3", "unittest", Path("data"), Path("out"), "demo")
    assert _as_text(run).endswith("-m unittest discover -s .")
    assert python_roots(tmp_path, [source]) == [tmp_path.resolve()]


def test_rust_lcov_path_is_absolute():
    report = Path("/tmp/bookwriter/target/coverage/rust/src-tauri/lcov.info")
    command = rust_coverage_command("llvm-cov", report)
    assert command == ["cargo", "llvm-cov", "--lcov", "--output-path", str(report)]


def test_vitest_version_and_provider_install(tmp_path, monkeypatch, capsys):
    assert _vitest_version(tmp_path) is None
    _write(tmp_path, "node_modules/vitest/package.json", "{not json")
    assert _vitest_version(tmp_path) is None
    assert "package.json" in capsys.readouterr().err
    _write(tmp_path, "node_modules/vitest/package.json", '{"version": "4.1.11"}\n')
    assert _vitest_version(tmp_path) == "4.1.11"
    provider = tmp_path / "node_modules" / "@vitest" / "coverage-v8"
    provider.mkdir(parents=True)
    assert _ensure_vitest_coverage(tmp_path) is True
    provider.rmdir()
    monkeypatch.setattr("crapper.runners.run_shell", lambda command, cwd: 1)
    assert _ensure_vitest_coverage(tmp_path) is False


def test_clean_clojure_keeps_other_language_reports(tmp_path):
    kept = tmp_path / "target" / "coverage" / "python"
    kept.mkdir(parents=True)
    (kept / "lcov.info").write_text("keep", encoding="utf-8")
    stale = tmp_path / "target" / "coverage" / "lcov.info"
    stale.write_text("old", encoding="utf-8")
    _clean_clojure(tmp_path)
    assert (kept / "lcov.info").is_file()
    assert not stale.exists()


def test_python_executable_prefers_a_project_venv(tmp_path):
    binary = tmp_path / ".venv" / "bin" / "python"
    binary.parent.mkdir(parents=True)
    binary.write_text("", encoding="utf-8")
    assert _python_executable(tmp_path) == str(binary)


def test_rust_kind_installs_llvm_cov_when_it_is_missing(monkeypatch):
    installed = {"llvm": False}

    def which(name):
        if name == "cargo-llvm-cov" and installed["llvm"]:
            return "/bin/cargo-llvm-cov"
        if name == "rustup":
            return "/bin/rustup"
        return None

    def shell(command, cwd):
        if "cargo install" in _as_text(command):
            installed["llvm"] = True
        return 0

    monkeypatch.setattr("crapper.runners.shutil.which", which)
    monkeypatch.setattr("crapper.runners.run_shell", shell)
    assert _rust_kind() == "llvm-cov"
    installed["llvm"] = False
    monkeypatch.setattr("crapper.runners.run_shell", lambda command, cwd: 1)
    assert _rust_kind() is None


def test_coverage_commands_are_issued_per_language(tmp_path, monkeypatch):
    commands: list[str | list[str]] = []

    def shell(command, cwd):
        commands.append(command)
        text = _as_text(command)
        if "--data-file=" in text:
            data = Path(text.split("--data-file=", 1)[1].split()[0])
            data.parent.mkdir(parents=True, exist_ok=True)
            data.write_text("x", encoding="utf-8")
            return 1
        if text.startswith("clj") or text.startswith("mvn") or text.startswith("go "):
            return 1
        if "vitest" in text or text.startswith("cargo "):
            return 1
        return 0

    monkeypatch.setattr("crapper.runners.run_shell", shell)
    monkeypatch.setattr(
        "crapper.runners.shutil.which",
        lambda name: "/bin/cargo-llvm-cov" if name == "cargo-llvm-cov" else None,
    )
    _write(tmp_path, "deps.edn", "{}\n")
    _write(tmp_path, "src/demo/core.clj", "(ns demo.core)\n(defn x [])\n")
    _write(tmp_path, "pom.xml", "<project></project>\n")
    _write(tmp_path, "src/Board.java", "class Board { int place(){ return 1; } }\n")
    (tmp_path / "target" / "jacoco.exec").parent.mkdir(parents=True)
    (tmp_path / "target" / "jacoco.exec").write_text("x", encoding="utf-8")
    _write(tmp_path, "go.mod", "module example.com/demo\n")
    _write(tmp_path, "board.go", "package demo\nfunc Place() int { return 1 }\n")
    profile = tmp_path / "target" / "coverage" / "go" / "coverage.out"
    profile.parent.mkdir(parents=True)
    profile.write_text("old", encoding="utf-8")
    _write(tmp_path, "package.json", json.dumps({"scripts": {"test": "vitest run"}}))
    _write(tmp_path, "src/ui.ts", "export function view(){ return 1 }\n")
    (tmp_path / "node_modules" / "@vitest" / "coverage-v8").mkdir(parents=True)
    _write(tmp_path, "pyproject.toml", "[tool.pytest.ini_options]\n")
    _write(tmp_path, "src/app.py", "def run():\n    return 1\n")
    _write(tmp_path, "Cargo.toml", '[package]\nname = "demo"\nversion = "0.1.0"\n')
    _write(tmp_path, "src/lib.rs", "pub fn open() {}\n")
    files = [
        tmp_path / "src/demo/core.clj",
        tmp_path / "src/Board.java",
        tmp_path / "board.go",
        tmp_path / "src/ui.ts",
        tmp_path / "src/app.py",
        tmp_path / "src/lib.rs",
    ]
    run_coverage(tmp_path, files, None)
    text = "\n".join(_as_text(command) for command in commands)
    assert "clj -M:cov --lcov" in text
    assert "clj -M:cov\n" in text or text.endswith("clj -M:cov") or "clj -M:cov" in text
    assert "mvn " in text
    assert "go test" in text
    assert "vitest" in text
    assert "coverage run" in text
    assert "cargo llvm-cov" in text
    run_coverage(tmp_path, files, "echo custom")
    assert commands[-1] == "echo custom"


def test_missing_build_files_skip_that_language(tmp_path, monkeypatch):
    monkeypatch.setattr("crapper.runners.run_shell", lambda command, cwd: 0)
    files = [
        _write(tmp_path, "src/App.java", "class App {}\n"),
        _write(tmp_path, "main.go", "package main\n"),
        _write(tmp_path, "src/app.ts", "export const n = 1\n"),
        _write(tmp_path, "src/lib.rs", "pub fn open() {}\n"),
    ]
    run_coverage(tmp_path, files, None)


def test_run_shell_returns_the_child_status_and_reports_a_failed_start(tmp_path, monkeypatch, capsys):
    assert run_shell("exit 3", tmp_path) == 3

    def boom(*_args, **_kwargs):
        raise OSError("nope")

    monkeypatch.setattr("crapper.runners.subprocess.run", boom)
    assert run_shell("exit 3", tmp_path) == 127
    assert "failed to start" in capsys.readouterr().err


def test_clean_removes_a_stale_directory_and_ignores_a_missing_one(tmp_path):
    coverage = tmp_path / "target" / "coverage"
    stale = coverage / "old"
    stale.mkdir(parents=True)
    (stale / "index.html").write_text("x", encoding="utf-8")
    (coverage / "notes.txt").write_text("keep", encoding="utf-8")
    nested = coverage / "notes"
    nested.mkdir()
    (nested / "keep.txt").write_text("x", encoding="utf-8")
    kept = coverage / "python"
    kept.mkdir()
    (kept / "index.html").write_text("py", encoding="utf-8")
    _clean_clojure(tmp_path)
    assert not stale.exists()
    assert (coverage / "notes.txt").is_file()
    assert (nested / "keep.txt").is_file()
    assert (kept / "index.html").is_file()
    assert kept.is_dir()
    _clean_dir(tmp_path / "target" / "coverage")
    assert not (tmp_path / "target" / "coverage").exists()
    _clean_clojure(tmp_path)
    _clean_dir(tmp_path / "missing")


def test_package_json_without_a_test_script_has_no_coverage_command(tmp_path):
    assert typescript_command(tmp_path, [], tmp_path / "cov") is None
    _write(tmp_path, "package.json", "{")
    with pytest.raises(PackageJsonError, match="package.json"):
        typescript_command(tmp_path, [], tmp_path / "cov")
    _write(tmp_path, "package.json", "[]")
    with pytest.raises(PackageJsonError, match="expected a JSON object"):
        typescript_command(tmp_path, [], tmp_path / "cov")
    _write(tmp_path, "package.json", '{"scripts": ["nope"]}')
    assert typescript_command(tmp_path, [], tmp_path / "cov") is None


def test_vitest_include_skips_a_file_outside_the_package(tmp_path):
    from crapper.runners import _vitest_includes

    package = tmp_path / "web"
    _write(package, "src/app.ts", "export const n = 1\n")
    outside = Path("/tmp/crapper-not-in-package.ts")
    assert _vitest_includes(package, [package / "src" / "app.ts", outside]) == ["src/app.ts"]


def test_nested_module_coverage_report_is_slugged(tmp_path):
    module = tmp_path / "src-tauri"
    module.mkdir()
    report = _coverage_report(tmp_path, module, "rust")
    assert report == tmp_path / "target" / "coverage" / "rust" / "src-tauri" / "lcov.info"


def test_prepare_report_replaces_an_existing_directory(tmp_path):
    report = tmp_path / "target" / "coverage" / "typescript" / "lcov.info"
    report.parent.mkdir(parents=True)
    (report.parent / "old.info").write_text("old", encoding="utf-8")
    _prepare_report(report)
    assert report.parent.is_dir()
    assert not (report.parent / "old.info").exists()


def test_pytest_detection_and_the_unittest_fallback(tmp_path, monkeypatch, capsys):
    assert uses_pytest(tmp_path) is False
    _write(tmp_path, "conftest.py", "")
    assert uses_pytest(tmp_path) is True
    monkeypatch.setattr("crapper.runners.run_shell", lambda *_args: 0)
    assert _python_kind("python3", tmp_path) == "pytest"
    bare = tmp_path / "bare"
    bare.mkdir()
    assert _python_kind("python3", bare) == "unittest"
    monkeypatch.setattr("crapper.runners._ensure_python_module", lambda *_args: False)
    assert _python_kind("python3", tmp_path) == "unittest"
    assert "Falling back to unittest" in capsys.readouterr().err


def test_python_sources_ignore_a_file_outside_the_package(tmp_path):
    inside = _write(tmp_path, "src/app.py", "def run():\n    return 1\n")
    assert python_sources(tmp_path, [inside, Path("/tmp/crapper-outside.py")]) == "src"


def test_missing_python_module_is_installed(tmp_path, monkeypatch):
    commands = []

    def shell(command, _cwd):
        commands.append(command)
        if _as_text(command).startswith("python3 -c"):
            return 1
        return 0

    monkeypatch.setattr("crapper.runners.run_shell", shell)
    assert _ensure_python_module("python3", tmp_path, "coverage") is True
    assert any("pip install" in _as_text(command) for command in commands)
    commands.clear()

    def already(_command, _cwd):
        commands.append("probe")
        return 0

    monkeypatch.setattr("crapper.runners.run_shell", already)
    assert _ensure_python_module("python3", tmp_path, "coverage") is True
    assert commands == ["probe"]


def test_python_lcov_warnings(tmp_path, monkeypatch, capsys):
    data = tmp_path / ".coverage"
    monkeypatch.setattr("crapper.runners.run_shell", lambda *_args: 1)
    _record_python_lcov(tmp_path, 1, data, "lcov")
    assert "Python coverage exited 1" in capsys.readouterr().err
    _record_python_lcov(tmp_path, 0, data, "lcov")
    assert capsys.readouterr().err == ""
    data.write_text("x", encoding="utf-8")
    _record_python_lcov(tmp_path, 0, data, "lcov")
    assert "coverage lcov exited 1" in capsys.readouterr().err
    monkeypatch.setattr("crapper.runners.run_shell", lambda *_args: 0)
    _record_python_lcov(tmp_path, 7, data, "lcov")
    assert "Coverage was still recorded" in capsys.readouterr().err


def test_custom_coverage_command_failure_is_reported(tmp_path, capsys):
    run_coverage(tmp_path, [], "exit 9")
    assert "exited 9" in capsys.readouterr().err


def test_clojure_without_a_build_file_is_skipped(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr("crapper.runners.run_shell", lambda *_args: 0)
    source = _write(tmp_path, "src/demo/core.clj", "(ns demo.core)\n(defn x [] 1)\n")
    run_coverage(tmp_path, [source], None)
    assert "skipping Clojure coverage" in capsys.readouterr().err


def test_rust_kind_none_skips_rust_coverage(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr("crapper.runners._rust_kind", lambda: None)
    monkeypatch.setattr("crapper.runners.run_shell", lambda *_args: 0)
    source = _write(tmp_path, "src/lib.rs", "pub fn open() {}\n")
    _write(tmp_path, "Cargo.toml", '[package]\nname = "demo"\nversion = "0.1.0"\n')
    run_coverage(tmp_path, [source], None)
    assert "cargo" not in capsys.readouterr().err


def test_tarpaulin_command_and_kind(monkeypatch):
    monkeypatch.setattr(
        "crapper.runners.shutil.which",
        lambda name: "/bin/cargo-tarpaulin" if name == "cargo-tarpaulin" else None,
    )
    assert _rust_kind() == "tarpaulin"
    report = Path("/tmp/book/target/coverage/rust/lcov.info")
    assert "tarpaulin" in rust_coverage_command("tarpaulin", report)


def test_typescript_without_a_test_script_is_skipped(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr("crapper.runners.run_shell", lambda *_args: 0)
    _write(tmp_path, "package.json", '{"scripts": {}}')
    source = _write(tmp_path, "src/app.ts", "export const n = 1\n")
    run_coverage(tmp_path, [source], None)
    assert "No package.json test script" in capsys.readouterr().err


def test_vitest_provider_failure_skips_typescript(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr("crapper.runners.run_shell", lambda *_args: 1)
    _write(tmp_path, "package.json", '{"scripts": {"test": "vitest run"}}')
    source = _write(tmp_path, "src/app.ts", "export const n = 1\n")
    run_coverage(tmp_path, [source], None)
    assert "coverage provider is missing" in capsys.readouterr().err


def test_python_coverage_module_missing_is_reported(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr("crapper.runners._ensure_python_module", lambda *_args: False)
    monkeypatch.setattr("crapper.runners.run_shell", lambda *_args: 0)
    _write(tmp_path, "pyproject.toml", "[tool.pytest.ini_options]\n")
    source = _write(tmp_path, "src/app.py", "def run():\n    return 1\n")
    run_coverage(tmp_path, [source], None)
    assert "coverage is missing" in capsys.readouterr().err


def test_an_argument_vector_is_not_a_shell(tmp_path, monkeypatch):
    seen = []

    def fake(command, cwd, shell=False):
        seen.append((command, shell))

        class Done:
            returncode = 0

        return Done()

    monkeypatch.setattr("crapper.runners.subprocess.run", fake)
    assert run_shell("exit 3", tmp_path) == 0
    marker = tmp_path / "pwned"
    assert run_shell(["true", f"a;touch {marker}"], tmp_path) == 0
    assert seen[0] == ("exit 3", True)
    assert seen[1][1] is False
    assert seen[1][0] == ["true", f"a;touch {marker}"]


def test_an_argument_vector_cannot_run_a_second_command(tmp_path):
    marker = tmp_path / "pwned"
    assert run_shell(["true", f"a;touch {marker}"], tmp_path) == 0
    assert not marker.exists()


def test_go_coverprofile_stays_one_argument(tmp_path, monkeypatch):
    root = tmp_path / "my module"
    root.mkdir()
    _write(root, "go.mod", "module example.com/demo\n")
    source = _write(root, "board.go", "package demo\nfunc Place() int { return 1 }\n")
    seen = []
    monkeypatch.setattr("crapper.runners.run_shell", lambda command, cwd: seen.append(command) or 0)
    run_coverage(root, [source], None)
    command = seen[0]
    assert command[:3] == ["go", "test", "./..."]
    assert command[3].startswith("-coverprofile=")
    assert " " in command[3]
    assert len(command) == 4


def test_invalid_package_json_is_not_a_missing_test_script(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr("crapper.runners.run_shell", lambda *_args: 0)
    _write(tmp_path, "package.json", "{ not json")
    source = _write(tmp_path, "src/app.ts", "export const n = 1\n")
    run_coverage(tmp_path, [source], None)
    err = capsys.readouterr().err
    assert "package.json" in err
    assert "No package.json test script" not in err


def test_vitest_install_restores_manifests_when_install_changes_them(tmp_path, monkeypatch):
    package_json = tmp_path / "package.json"
    package_json.write_bytes(b'{"name":"demo"}\n')

    def shell(command, cwd):
        package_json.write_bytes(b'{"name":"changed"}\n')
        return 1

    monkeypatch.setattr("crapper.runners.run_shell", shell)
    assert _ensure_vitest_coverage(tmp_path) is False
    assert package_json.read_bytes() == b'{"name":"demo"}\n'


def test_python_sources_name_a_top_level_module_without_its_suffix(tmp_path):
    module = tmp_path / "carddav.py"
    assert python_sources(tmp_path, [module, tmp_path / "test_carddav.py"]) == "carddav"
