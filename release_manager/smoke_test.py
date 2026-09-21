import os
import sys

CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))
if CURRENT_DIR not in sys.path:
    sys.path.insert(0, CURRENT_DIR)

from core.docker_handler import DockerHandler
from core.maven_handler import MavenHandler
from utils.common_dict import CommonDict
from utils.config_manager import ConfigManager


class ConsoleBuffer:
    def __init__(self):
        self.lines = []

    def append(self, message):
        self.lines.append(str(message))


class FakeCommandExecutor:
    def __init__(self):
        self.commands = []

    def run_command(self, command, cwd=None, env=None, output_callback=None):
        self.commands.append(
            {
                "command": command,
                "cwd": cwd,
                "env": env,
            }
        )
        if output_callback:
            output_callback(f"[TEST] {command}")


def assert_true(condition, message):
    if not condition:
        raise AssertionError(message)


def main():
    ConfigManager.init_config()
    console = ConsoleBuffer()
    executor = FakeCommandExecutor()

    project_root = ConfigManager.get_project_root()
    config_file = ConfigManager.get_config_file_path()
    assert_true(
        config_file.endswith(os.path.join("release_manager", "runtime", "config.json")),
        f"配置文件路径异常: {config_file}",
    )
    assert_true(os.path.isdir(project_root), f"项目根目录不存在: {project_root}")
    assert_true(
        ConfigManager.get_config_value(
            CommonDict.PREFERRED_CLOUD_VENDOR,
            ConfigManager.get_default_cloud_vendor(),
        )
        == ConfigManager.get_default_cloud_vendor(),
        "默认云厂商预设异常",
    )
    assert_true(
        ConfigManager.get_config_value(
            CommonDict.PREFERRED_JAVA_DISTRIBUTION,
            ConfigManager.get_default_java_distribution(),
        )
        == ConfigManager.get_default_java_distribution(),
        "默认 JDK 发行版配置异常",
    )
    assert_true(
        ConfigManager.get_default_java_distribution("aliyun") == "dragonwell",
        "阿里云默认 JDK 发行版异常",
    )
    assert_true(
        ConfigManager.get_default_java_distribution("huawei") == "bisheng",
        "华为云默认 JDK 发行版异常",
    )
    assert_true(
        ConfigManager.get_default_java_distribution("tencent") == "kona",
        "腾讯云默认 JDK 发行版异常",
    )
    assert_true(
        ConfigManager.get_default_docker_base_image_version("25", "temurin")
        == "eclipse-temurin:25-jre-alpine",
        "Temurin 默认 Docker 基础镜像异常",
    )
    assert_true(
        "alibabadragonwell/dragonwell:25-anolis"
        in ConfigManager.get_docker_base_image_versions("dragonwell", "25"),
        "Dragonwell 镜像候选未生效",
    )

    user_service_domain = "services/user"
    user_service_module = f"{user_service_domain}/im-user-service"
    access_ws_service_module = "gateways/im-access-ws"
    user_service_pom = os.path.join(project_root, *user_service_module.split("/"), "pom.xml")
    artifact_id, version = MavenHandler.get_artifact_and_version(user_service_pom)
    assert_true(artifact_id == "im-user-service", f"artifactId 解析错误: {artifact_id}")
    assert_true(version == "1.0.0-SNAPSHOT", f"version 解析错误: {version}")

    sub_projects = MavenHandler.find_sub_projects(console, project_root)
    sub_project_names = {item["name"] for item in sub_projects}
    assert_true(
        f"/{user_service_domain}" in sub_project_names,
        "未发现 im-user 独立服务域",
    )
    assert_true(
        f"/{access_ws_service_module}" in sub_project_names,
        "未发现 im-access-ws 发布目标",
    )

    MavenHandler.build_maven_project(console, f"/{user_service_domain}", executor)
    assert_true(len(executor.commands) == 1, "Maven 构建命令未被记录")
    assert_true(
        '-pl "services/user" -am clean package'
        in executor.commands[0]["command"],
        f"Maven 命令异常: {executor.commands[0]['command']}",
    )
    assert_true(
        executor.commands[0]["cwd"] == project_root,
        f"Maven 构建目录异常: {executor.commands[0]['cwd']}",
    )

    assert_true(
        "mvnw.ps1" in executor.commands[0]["command"],
        f"Maven Wrapper 未生效: {executor.commands[0]['command']}",
    )

    assert_true(
        MavenHandler._normalize_sub_project_dir(user_service_module)
        == user_service_module,
        "Docker 部署模块路径解析异常",
    )

    print("release_manager smoke test passed")


if __name__ == "__main__":
    main()
