import glob
import os
import shutil
from ui.utilities import run_shell_command
from utils.common_dict import CommonDict
from utils.config_manager import ConfigManager
from utils.template_utils import DockerTemplateUtil


class DockerHandler:
    @staticmethod
    def _normalize_sub_project_dir(sub_project_dir):
        return sub_project_dir.lstrip("/\\").replace("\\", "/")

    @staticmethod
    def _find_source_jar(root_dir, sub_project_dir, artifact_id):
        target_dir = os.path.join(root_dir, sub_project_dir, "target")
        jar_patterns = [
            os.path.join(target_dir, f"{artifact_id}-*.jar"),
            os.path.join(target_dir, f"{artifact_id}.jar"),
        ]

        candidates = []
        for pattern in jar_patterns:
            candidates.extend(glob.glob(pattern))

        filtered_candidates = [
            candidate
            for candidate in candidates
            if not candidate.endswith(".original")
            and "-sources" not in os.path.basename(candidate)
            and "-javadoc" not in os.path.basename(candidate)
            and "-plain" not in os.path.basename(candidate)
        ]

        if not filtered_candidates:
            raise FileNotFoundError(
                f"未找到可用于 Docker 打包的 JAR 文件: {target_dir}"
            )

        filtered_candidates.sort(key=os.path.getmtime, reverse=True)
        return filtered_candidates[0]

    @staticmethod
    def _build_image_name(artifact_id, version):
        registry = (
            ConfigManager.get_config_value(CommonDict.DOCKER_REGISTRY, "") or ""
        ).strip().strip("/")
        namespace = (
            ConfigManager.get_config_value(CommonDict.DOCKER_NAMESPACE, "") or ""
        ).strip().strip("/")

        repository = artifact_id.lower()
        if namespace:
            repository = f"{namespace}/{repository}"

        if registry:
            return f"{registry}/{repository}:{version}"
        return f"{repository}:{version}"

    @staticmethod
    def _run_command(
        command, root_dir, console_output_widget, command_executor=None
    ):
        if command_executor:
            command_executor.run_command(
                command, cwd=root_dir, output_callback=console_output_widget.append
            )
        else:
            run_shell_command(
                command, cwd=root_dir, output_callback=console_output_widget.append
            )

    @staticmethod
    def build_and_push_docker_image(
        console_output_widget,
        artifact_id,
        version,
        root_dir,
        sub_project_dir,
        deployment_environment,
        command_executor=None,
    ):
        """
        构建并推送 Docker 镜像，根据不同的部署环境配置 OTEL Endpoint

        Args:
            console_output_widget: 控制台输出组件
            artifact_id: 制品ID
            version: 版本号
            root_dir: 根目录
            sub_project_dir: 子项目目录
            deployment_environment: 部署环境
            command_executor: 命令执行器(可选)
        """
        # 添加参数验证
        if not all([artifact_id, version, root_dir, sub_project_dir]):
            raise ValueError("必需的参数不能为空")

        try:
            normalized_sub_project_dir = DockerHandler._normalize_sub_project_dir(
                sub_project_dir
            )
            console_output_widget.append(
                f"[INFO] 开始构建 Docker 镜像: {artifact_id}:{version}"
            )

            build_output_dir = os.path.join(
                root_dir,
                "release_manager",
                "build_output",
                normalized_sub_project_dir.replace("/", "_"),
            )
            if os.path.exists(build_output_dir):
                shutil.rmtree(build_output_dir)
            os.makedirs(build_output_dir, exist_ok=True)

            source_jar_path = DockerHandler._find_source_jar(
                root_dir, normalized_sub_project_dir, artifact_id
            )
            target_jar_name = os.path.basename(source_jar_path)
            target_jar_path = os.path.join(build_output_dir, target_jar_name)
            shutil.copy2(source_jar_path, target_jar_path)
            console_output_widget.append(
                f"[INFO] JAR 文件已复制到 build_output 目录: {target_jar_path}"
            )

            template_path = ConfigManager.get_resource_path("Dockerfile.template")
            template_str = DockerTemplateUtil.read_template(template_path)

            enable_otel = ConfigManager.get_config_value(
                CommonDict.OTEL_MONITORING, False
            )
            enable_arthas = ConfigManager.get_config_value(CommonDict.ARTHAS, False)

            config = {
                "docker_base_image_version": ConfigManager.get_config_value(
                    CommonDict.DOCKER_BASE_IMAGE_VERSION
                ),
                "open_telemetry_file": (
                    "# OpenTelemetry 链路追踪Agent\n"
                    "COPY tools/opentelemetry-javaagent.jar /opt/opentelemetry-javaagent.jar"
                    if enable_otel
                    else ""
                ),
                "sh_entrypoint": (
                    (
                        f'ENTRYPOINT [ "sh", "-c", "java -javaagent:/opt/opentelemetry-javaagent.jar '
                        f"-Dotel.resource.attributes=service.name={artifact_id},service.version={version},"
                        "deployment.environment=${DEPLOY_ENV} "
                        "-Dotel.exporter.otlp.protocol=http/protobuf "
                        "-Dotel.exporter.otlp.traces.endpoint=${OTEL_ENDPOINT} "
                        "-Dotel.exporter.otlp.metrics.endpoint=${OTEL_METRICS_ENDPOINT} "
                        "-Dotel.logs.exporter=none "
                        '-jar -Djava.security.egd=file:/dev/./urandom /app.jar" ]'
                    )
                    if enable_otel
                    else 'ENTRYPOINT [ "sh", "-c", "java -jar -Djava.security.egd=file:/dev/./urandom /app.jar" ]'
                ),
                "arthas_file": (
                    "# arthas性能监控工具\n"
                    "COPY tools/arthas-boot.jar /opt/arthas-boot.jar"
                    if enable_arthas
                    else ""
                ),
            }

            dockerfile_content = DockerTemplateUtil.replace_template(
                template_str, config
            )

            dockerfile_path = os.path.join(build_output_dir, "Dockerfile")
            with open(dockerfile_path, "w", encoding="utf-8") as file:
                file.write(dockerfile_content)

            tool_root = ConfigManager.get_release_manager_root()
            bundled_tools_dir = os.path.join(tool_root, "tools")
            if (enable_otel or enable_arthas) and os.path.exists(bundled_tools_dir):
                shutil.copytree(
                    bundled_tools_dir, os.path.join(build_output_dir, "tools")
                )

            image_name = DockerHandler._build_image_name(artifact_id, version)

            build_command = (
                f"docker build --build-arg JAR_FILE={target_jar_name} "
                f'-f "{dockerfile_path}" -t {image_name} "{build_output_dir}"'
            )

            registry = ConfigManager.get_config_value(CommonDict.DOCKER_REGISTRY, "")
            username = ConfigManager.get_config_value(CommonDict.DOCKER_USERNAME, "")
            password = ConfigManager.get_config_value(CommonDict.DOCKER_PASSWORD, "")
            push_command = f"docker push {image_name}"

            if registry and username and password:
                login_command = (
                    f'docker login {registry} --username "{username}" '
                    f'--password "{password}"'
                )
                DockerHandler._run_command(
                    login_command, root_dir, console_output_widget, command_executor
                )
            elif registry:
                console_output_widget.append(
                    "[WARNING] 未配置 Docker 用户名/密码，将直接使用当前 Docker 登录态继续执行。"
                )

            DockerHandler._run_command(
                build_command, root_dir, console_output_widget, command_executor
            )

            if registry:
                DockerHandler._run_command(
                    push_command, root_dir, console_output_widget, command_executor
                )
                console_output_widget.append(
                    f"[SUCCESS] Docker 镜像已推送: {image_name}"
                )
            else:
                console_output_widget.append(
                    f"[SUCCESS] Docker 镜像已构建（未推送）: {image_name}"
                )

        except Exception as e:
            console_output_widget.append(f"[ERROR] Docker 构建失败: {str(e)}")
            raise

    @staticmethod
    def get_otel_endpoints(deployment_environment):
        """
        根据部署环境获取 OTEL 的 Endpoint 和 Metrics Endpoint
        """
        if (
            deployment_environment == "dev"
            or deployment_environment == "test"
            or deployment_environment == "prod"
        ):
            # 开发 测试 大中华生产
            return (
                ConfigManager.get_config_value(CommonDict.OTEL_ENDPOINT_CN),
                ConfigManager.get_config_value(CommonDict.OTEL_METRICS_ENDPOINT_CN),
            )
        elif deployment_environment == "prod_na":
            # 北美生产
            return (
                ConfigManager.get_config_value(CommonDict.OTEL_ENDPOINT_NA),
                ConfigManager.get_config_value(CommonDict.OTEL_METRICS_ENDPOINT_NA),
            )
        elif deployment_environment == "prod_eu":
            # 欧洲生产
            return (
                ConfigManager.get_config_value(CommonDict.OTEL_ENDPOINT_EU),
                ConfigManager.get_config_value(CommonDict.OTEL_METRICS_ENDPOINT_EU),
            )
        else:
            return None, None
