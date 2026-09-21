import subprocess
import sys
from pathlib import Path


def get_requirements_file():
    release_manager_root = Path(__file__).resolve().parent.parent
    version_tag = f"py{sys.version_info.major}{sys.version_info.minor}"
    candidates = [
        release_manager_root / f"requirements-{version_tag}.txt",
        release_manager_root / "requirements.txt",
    ]
    for requirement_file in candidates:
        if requirement_file.exists():
            return requirement_file
    return None


def check_and_install_packages():
    """
    检查并安装所需的依赖包。
    """
    required_packages = {
        "rich": "rich.console",
        "lxml": "lxml.etree",
        "PySide6": "PySide6",
        "qdarkstyle": "qdarkstyle",
    }

    mirror_url = (
        "https://mirrors.aliyun.com/pypi/simple/"  # 使用阿里云的镜像源，加快安装速度
    )

    for package, module_name in required_packages.items():
        try:
            __import__(module_name)  # 动态导入模块
        except ImportError:
            print(f"未检测到 {package}，正在安装 {package}...")
            try:
                requirements_file = get_requirements_file()
                if requirements_file is not None:
                    subprocess.check_call(
                        [
                            sys.executable,
                            "-m",
                            "pip",
                            "install",
                            "-r",
                            str(requirements_file),
                            "-i",
                            mirror_url,
                        ]
                    )
                else:
                    subprocess.check_call(
                        [
                            sys.executable,
                            "-m",
                            "pip",
                            "install",
                            package,
                            "-i",
                            mirror_url,
                        ]
                    )
                print(f"{package} 安装成功！")
            except subprocess.CalledProcessError as e:
                print(f"安装 {package} 失败: {str(e)}")
                sys.exit(1)
            break


def check_external_tools():
    """
    检查系统环境中是否安装了外部工具，如Maven和Docker。
    """
    tools = ["mvn", "docker"]
    for tool in tools:
        try:
            subprocess.run(
                f"{tool} --version",
                check=True,
                shell=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            print(f"{tool} 已正确安装。")
        except (subprocess.CalledProcessError, FileNotFoundError):
            print(f"[ERROR] 未检测到 {tool}，请先安装 {tool}。")


def setup_environment():
    """
    检查并安装Python依赖包和外部工具。
    """
    print(
        f"当前 Python: {sys.executable} ("
        f"{sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro})"
    )
    check_and_install_packages()  # 检查并安装依赖包
    check_external_tools()  # 检查外部工具
