import os
import re
from lxml import etree
from core.release_target import ReleaseTargetResolver
from ui.utilities import run_shell_command
from utils.common_dict import CommonDict
from utils.config_manager import ConfigManager

try:
    import winreg
except ImportError:
    winreg = None


class MavenHandler:
    MAVEN_PACKAGE_COMMAND = (
        '{maven_command} -pl "{module}" -am clean package -DskipTests '
        '"-Dmaven.compiler.useIncrementalCompilation=false"'
    )
    MAVEN_DEPLOY_COMMAND = (
        '{maven_command} -pl "{module}" -am clean deploy '
        '"-Dmaven.compiler.useIncrementalCompilation=false"'
    )

    @staticmethod
    def _parse_java_major(version_text):
        if not version_text:
            return None

        version_text = version_text.strip().strip('"')
        if version_text.startswith("${") and version_text.endswith("}"):
            return None

        legacy_match = re.match(r"1\.(\d+)", version_text)
        if legacy_match:
            return int(legacy_match.group(1))

        match = re.match(r"(\d+)", version_text)
        return int(match.group(1)) if match else None

    @staticmethod
    def _get_parent_pom_file(pom_file, root, namespace):
        parent = root.find("mvn:parent", namespaces=namespace)
        if parent is None:
            return None

        relative_path = parent.find("mvn:relativePath", namespaces=namespace)
        relative_path_text = "../pom.xml"
        if relative_path is not None and relative_path.text:
            relative_path_text = relative_path.text.strip()

        parent_pom_file = os.path.abspath(
            os.path.join(os.path.dirname(pom_file), relative_path_text)
        )
        return parent_pom_file if os.path.exists(parent_pom_file) else None

    @staticmethod
    def _normalize_sub_project_dir(sub_project_dir):
        return sub_project_dir.lstrip("/\\").replace("\\", "/")

    @staticmethod
    def _resolve_maven_command(project_root_dir):
        if os.name == "nt":
            wrapper_path = os.path.join(project_root_dir, "mvnw.ps1")
            if not os.path.isfile(wrapper_path):
                raise FileNotFoundError(f"未找到 Maven Wrapper: {wrapper_path}")
            return f'powershell.exe -NoProfile -ExecutionPolicy Bypass -File "{wrapper_path}"'
        wrapper_path = os.path.join(project_root_dir, "mvnw")
        if not os.path.isfile(wrapper_path):
            raise FileNotFoundError(f"未找到 Maven Wrapper: {wrapper_path}")
        return wrapper_path

    @staticmethod
    def _resolve_effective_version_text(pom_file, visited=None):
        if visited is None:
            visited = set()

        normalized_path = os.path.abspath(pom_file)
        if normalized_path in visited or not os.path.exists(normalized_path):
            return None
        visited.add(normalized_path)

        tree = etree.parse(normalized_path)
        root = tree.getroot()
        namespace = {"mvn": "http://maven.apache.org/POM/4.0.0"}

        version_element = root.find("mvn:version", namespaces=namespace)
        if version_element is not None and version_element.text:
            return MavenHandler._resolve_version_placeholder(
                normalized_path, version_element.text.strip(), root, namespace, visited
            )

        parent = root.find("mvn:parent", namespaces=namespace)
        if parent is not None:
            parent_version = parent.find("mvn:version", namespaces=namespace)
            if parent_version is not None and parent_version.text:
                return MavenHandler._resolve_version_placeholder(
                    normalized_path,
                    parent_version.text.strip(),
                    root,
                    namespace,
                    visited,
                )

        parent_pom_file = MavenHandler._get_parent_pom_file(
            normalized_path, root, namespace
        )
        if parent_pom_file:
            return MavenHandler._resolve_effective_version_text(
                parent_pom_file, visited
            )
        return None

    @staticmethod
    def _resolve_version_placeholder(
        pom_file, version_text, root, namespace, visited=None
    ):
        version_text = version_text.strip()
        placeholder_match = re.fullmatch(r"\$\{([^}]+)\}", version_text)
        if not placeholder_match:
            return version_text

        property_name = placeholder_match.group(1)
        if property_name in ("project.version", "pom.version"):
            parent_pom_file = MavenHandler._get_parent_pom_file(
                pom_file, root, namespace
            )
            if parent_pom_file:
                return MavenHandler._resolve_effective_version_text(
                    parent_pom_file, visited
                )
            return None

        if property_name in ("project.parent.version", "parent.version"):
            parent = root.find("mvn:parent", namespaces=namespace)
            if parent is not None:
                parent_version = parent.find("mvn:version", namespaces=namespace)
                if parent_version is not None and parent_version.text:
                    return parent_version.text.strip()
            parent_pom_file = MavenHandler._get_parent_pom_file(
                pom_file, root, namespace
            )
            if parent_pom_file:
                return MavenHandler._resolve_effective_version_text(
                    parent_pom_file, visited
                )
            return None

        resolved_property = MavenHandler._resolve_property_value(
            pom_file, property_name, visited
        )
        return resolved_property or version_text

    @staticmethod
    def _resolve_property_value(pom_file, property_name, visited=None):
        if visited is None:
            visited = set()

        visit_key = (os.path.abspath(pom_file), property_name)
        if visit_key in visited or not os.path.exists(pom_file):
            return None
        visited.add(visit_key)

        tree = etree.parse(pom_file)
        root = tree.getroot()
        namespace = {"mvn": "http://maven.apache.org/POM/4.0.0"}

        properties_element = root.find("mvn:properties", namespaces=namespace)
        if properties_element is not None:
            for child in properties_element:
                tag_name = etree.QName(child).localname
                if tag_name != property_name or child.text is None:
                    continue

                value = child.text.strip()
                placeholder_match = re.fullmatch(r"\$\{([^}]+)\}", value)
                if placeholder_match:
                    return MavenHandler._resolve_property_value(
                        pom_file, placeholder_match.group(1), visited
                    )
                return value

        parent_pom_file = MavenHandler._get_parent_pom_file(pom_file, root, namespace)
        if parent_pom_file:
            return MavenHandler._resolve_property_value(
                parent_pom_file, property_name, visited
            )
        return None

    @staticmethod
    def _get_required_java_version(pom_file):
        for property_name in ("maven.compiler.release", "maven.compiler.source", "java.version"):
            property_value = MavenHandler._resolve_property_value(pom_file, property_name)
            parsed_version = MavenHandler._parse_java_major(property_value)
            if parsed_version:
                return parsed_version
        return 11

    @staticmethod
    def _infer_java_distribution(implementor_text, java_home):
        normalized_text = " ".join(
            filter(None, [str(implementor_text or ""), str(java_home or "")])
        ).lower()
        if "dragonwell" in normalized_text or "alibaba" in normalized_text:
            return "dragonwell"
        if "bisheng" in normalized_text or "huawei" in normalized_text:
            return "bisheng"
        if "kona" in normalized_text or "tencent" in normalized_text:
            return "kona"
        if "temurin" in normalized_text or "adoptium" in normalized_text:
            return "temurin"
        if "corretto" in normalized_text or "amazon" in normalized_text:
            return "corretto"
        if "microsoft" in normalized_text:
            return "microsoft"
        if "zulu" in normalized_text or "azul" in normalized_text:
            return "zulu"
        if "liberica" in normalized_text or "bellsoft" in normalized_text:
            return "liberica"
        return "openjdk"

    @staticmethod
    def _get_java_metadata_from_home(java_home):
        release_file = os.path.join(java_home, "release")
        if not os.path.exists(release_file):
            return None

        release_properties = {}
        try:
            with open(release_file, "r", encoding="utf-8") as file:
                for line in file:
                    if "=" not in line:
                        continue
                    key, value = line.split("=", 1)
                    release_properties[key.strip()] = value.strip().strip('"')
        except OSError:
            return None

        java_version = MavenHandler._parse_java_major(
            release_properties.get("JAVA_VERSION")
        )
        if not java_version:
            return None

        return {
            "version": java_version,
            "distribution": MavenHandler._infer_java_distribution(
                release_properties.get("IMPLEMENTOR"),
                java_home,
            ),
            "java_home": java_home,
            "implementor": release_properties.get("IMPLEMENTOR", ""),
        }

    @staticmethod
    def _find_available_java_homes():
        candidates = []
        seen_paths = set()

        java_home = os.environ.get("JAVA_HOME")
        if java_home:
            candidates.append(java_home)
        candidates.extend(MavenHandler._find_java_homes_from_windows_registry())

        user_home = os.path.expanduser("~")
        search_roots = [
            r"C:\Program Files\Java",
            r"C:\Program Files\Alibaba",
            r"C:\Program Files\Alibaba\Dragonwell",
            r"C:\Program Files\Huawei",
            r"C:\Program Files\Tencent",
            r"C:\Program Files\Eclipse Adoptium",
            r"C:\Program Files\Zulu",
            r"C:\Program Files\Microsoft",
            r"C:\Program Files\Amazon Corretto",
            r"C:\Program Files\BellSoft",
            r"C:\Program Files\LibericaJDK",
            os.path.join(
                user_home,
                "AppData",
                "Roaming",
                "Trae",
                "User",
                "globalStorage",
                "pleiades.java-extension-pack-jdk",
                "java",
            ),
        ]

        for root_dir in search_roots:
            if not os.path.isdir(root_dir):
                continue

            if os.path.exists(os.path.join(root_dir, "bin", "java.exe")):
                candidates.append(root_dir)

            try:
                for child_name in os.listdir(root_dir):
                    child_dir = os.path.join(root_dir, child_name)
                    if os.path.exists(os.path.join(child_dir, "bin", "java.exe")):
                        candidates.append(child_dir)
            except OSError:
                continue

        available_java_homes = []
        for candidate in candidates:
            normalized_candidate = os.path.abspath(candidate)
            if normalized_candidate in seen_paths:
                continue
            seen_paths.add(normalized_candidate)

            metadata = MavenHandler._get_java_metadata_from_home(normalized_candidate)
            if metadata:
                available_java_homes.append(metadata)

        return sorted(
            available_java_homes,
            key=lambda item: (item["version"], item["distribution"], item["java_home"]),
        )

    @staticmethod
    def _find_java_homes_from_windows_registry():
        if os.name != "nt" or winreg is None:
            return []

        registry_locations = [
            (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\JavaSoft\JDK"),
            (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\WOW6432Node\JavaSoft\JDK"),
            (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Eclipse Adoptium\JDK"),
            (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\WOW6432Node\Eclipse Adoptium\JDK"),
            (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Alibaba\Dragonwell"),
            (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\WOW6432Node\Alibaba\Dragonwell"),
            (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Huawei\BiSheng"),
            (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\WOW6432Node\Huawei\BiSheng"),
            (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Tencent\Kona"),
            (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\WOW6432Node\Tencent\Kona"),
            (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Microsoft\JDK"),
            (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\WOW6432Node\Microsoft\JDK"),
            (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Azul Systems\Zulu"),
            (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\WOW6432Node\Azul Systems\Zulu"),
            (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\BellSoft\LibericaJDK"),
            (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\WOW6432Node\BellSoft\LibericaJDK"),
        ]
        value_names = ("JavaHome", "Path", "InstallationPath")

        java_homes = []
        for root_key, sub_key in registry_locations:
            try:
                with winreg.OpenKey(root_key, sub_key) as key:
                    index = 0
                    while True:
                        try:
                            version_name = winreg.EnumKey(key, index)
                            index += 1
                        except OSError:
                            break

                        try:
                            with winreg.OpenKey(key, version_name) as version_key:
                                for value_name in value_names:
                                    try:
                                        java_home, _ = winreg.QueryValueEx(
                                            version_key, value_name
                                        )
                                    except OSError:
                                        continue

                                    if java_home and os.path.exists(
                                        os.path.join(java_home, "bin", "java.exe")
                                    ):
                                        java_homes.append(java_home)
                                        break
                        except OSError:
                            continue
            except OSError:
                continue

        return java_homes

    @staticmethod
    def _prepare_maven_environment(console_output_widget, pom_file):
        required_java_version = MavenHandler._get_required_java_version(pom_file)
        available_java_homes = MavenHandler._find_available_java_homes()
        preferred_java_distribution = str(
            ConfigManager.get_config_value(
                CommonDict.PREFERRED_JAVA_DISTRIBUTION,
                ConfigManager.get_default_java_distribution(),
            )
        )
        preferred_java_version = ConfigManager.get_config_value(
            CommonDict.PREFERRED_JAVA_VERSION,
            ConfigManager.get_default_java_version(),
        )
        try:
            preferred_java_version = int(preferred_java_version)
        except (TypeError, ValueError):
            preferred_java_version = int(ConfigManager.get_default_java_version())

        compatible_java_homes = [
            java_metadata
            for java_metadata in available_java_homes
            if java_metadata["version"] >= required_java_version
        ]

        selected_java = min(
            compatible_java_homes,
            key=lambda item: (
                0 if item["distribution"] == preferred_java_distribution else 1,
                0
                if item["version"] == preferred_java_version
                else 1 if item["version"] > preferred_java_version else 2,
                abs(item["version"] - preferred_java_version),
                item["version"],
                item["java_home"],
            ),
            default=None,
        )

        if not selected_java:
            console_output_widget.append(
                "[WARNING] 未找到兼容 JDK，继续使用当前环境执行 Maven。"
                f" 需要 JDK {required_java_version}+，偏好发行版: {preferred_java_distribution}，偏好版本: {preferred_java_version}"
            )
            return None

        selected_version = selected_java["version"]
        selected_java_home = selected_java["java_home"]
        selected_java_distribution = selected_java["distribution"]
        environment = os.environ.copy()
        environment["JAVA_HOME"] = selected_java_home
        java_bin_path = os.path.join(selected_java_home, "bin")
        original_path = environment.get("PATH") or environment.get("Path") or ""
        merged_path = java_bin_path
        if original_path:
            merged_path = java_bin_path + os.pathsep + original_path

        # Windows 环境中可能同时存在 PATH / Path 两种键名。
        # 为避免覆盖后导致 mvn 等命令丢失，这里统一同步两者。
        environment["PATH"] = merged_path
        environment["Path"] = merged_path
        console_output_widget.append(
            "[INFO] Maven 使用 JDK "
            f"{selected_version} / {CommonDict.JAVA_DISTRIBUTIONS.get(selected_java_distribution, selected_java_distribution)}: "
            f"{selected_java_home} (偏好: "
            f"{CommonDict.JAVA_DISTRIBUTIONS.get(preferred_java_distribution, preferred_java_distribution)} / "
            f"{preferred_java_version})"
        )
        return environment

    @staticmethod
    def find_sub_projects(console_output_widget, root_dir="."):
        """
        查找包含 pom.xml 的子项目目录，并识别可启动的服务
        """
        console_output_widget.append("[INFO] 正在查找子项目...")
        targets = ReleaseTargetResolver.discover(root_dir)
        for target in targets:
            console_output_widget.append(
                f"[INFO] 发现发布目标: {target.domain_module} -> {target.deploy_module}"
            )
        return [{"name": target.display_name, "color": "green"} for target in targets]

    @staticmethod
    def build_maven_project(
        console_output_widget, sub_project_dir, command_executor=None
    ):
        """
        执行 Maven 构建操作
        """
        console_output_widget.append(f"[INFO] 开始构建项目: {sub_project_dir}")
        project_root_dir = ConfigManager.get_config_value(CommonDict.PROJECT_ROOT_DIR)
        module_name = MavenHandler._normalize_sub_project_dir(sub_project_dir)
        target = ReleaseTargetResolver.resolve(project_root_dir, module_name)
        pom_file = os.path.join(project_root_dir, target.domain_module, "pom.xml")
        maven_env = MavenHandler._prepare_maven_environment(
            console_output_widget, pom_file
        )
        command = MavenHandler.MAVEN_PACKAGE_COMMAND.format(
            maven_command=MavenHandler._resolve_maven_command(project_root_dir),
            module=target.domain_module,
        )
        if command_executor:
            command_executor.run_command(
                command,
                project_root_dir,
                maven_env,
                console_output_widget.append,
            )
        else:
            run_shell_command(
                command,
                project_root_dir,
                maven_env,
                console_output_widget.append,
            )

    @staticmethod
    def release_maven_project(
        console_output_widget, sub_project_dir, command_executor=None
    ):
        """
        执行 Maven 发布操作
        """
        console_output_widget.append(f"[INFO] 正在发布项目: {sub_project_dir}")
        project_root_dir = ConfigManager.get_config_value(CommonDict.PROJECT_ROOT_DIR)
        module_name = MavenHandler._normalize_sub_project_dir(sub_project_dir)
        target = ReleaseTargetResolver.resolve(project_root_dir, module_name)
        pom_file = os.path.join(project_root_dir, target.domain_module, "pom.xml")
        maven_env = MavenHandler._prepare_maven_environment(
            console_output_widget, pom_file
        )
        command = MavenHandler.MAVEN_DEPLOY_COMMAND.format(
            maven_command=MavenHandler._resolve_maven_command(project_root_dir),
            module=target.domain_module,
        )
        if command_executor:
            command_executor.run_command(
                command,
                project_root_dir,
                maven_env,
                console_output_widget.append,
            )
        else:
            run_shell_command(
                command,
                project_root_dir,
                maven_env,
                console_output_widget.append,
            )

    @staticmethod
    def package_to_maven(console_output_widget, sub_project_dir, command_executor=None):
        """
        打包到 Maven 远程仓库
        """
        console_output_widget.append(
            f"[INFO] 正在将项目打包并上传到 Maven 远程仓库: {sub_project_dir} ..."
        )
        MavenHandler.release_maven_project(
            console_output_widget, sub_project_dir, command_executor
        )

    @staticmethod
    def get_artifact_and_version(pom_file):
        """
        从 pom.xml 中提取 artifactId 和 version 信息
        """
        tree = etree.parse(pom_file)
        root = tree.getroot()
        namespace = {"mvn": "http://maven.apache.org/POM/4.0.0"}

        artifact_id = root.find("mvn:artifactId", namespaces=namespace).text
        version = MavenHandler._resolve_effective_version_text(pom_file) or "未定义"

        return artifact_id, version

    @staticmethod
    def get_properties_values(pom_file):
        """
        解析 properties 标签中的值，处理命名空间
        """
        tree = etree.parse(pom_file)
        root = tree.getroot()
        namespace = {"mvn": "http://maven.apache.org/POM/4.0.0"}

        properties_element = root.find("mvn:properties", namespaces=namespace)
        properties = {}
        if properties_element is not None:
            for child in properties_element:
                if isinstance(child.tag, str):
                    tag_name = etree.QName(child).localname
                    properties[tag_name] = child.text
        return properties

    @staticmethod
    def update_release_target_version(console_output_widget, project_root_dir, target, new_version):
        pom_files = [
            os.path.join(project_root_dir, target.domain_module, "pom.xml"),
            os.path.join(project_root_dir, target.deploy_module, "pom.xml"),
        ]
        domain_pom = pom_files[0]
        domain_root = etree.parse(domain_pom).getroot()
        for module in domain_root.findall(
            "mvn:modules/mvn:module",
            namespaces={"mvn": "http://maven.apache.org/POM/4.0.0"},
        ):
            if not module.text or not module.text.strip().endswith("-api"):
                continue
            pom_files.append(
                os.path.join(
                    project_root_dir,
                    target.domain_module,
                    module.text.strip(),
                    "pom.xml",
                )
            )

        namespace = {"mvn": "http://maven.apache.org/POM/4.0.0"}
        for pom_file in pom_files:
            parser = etree.XMLParser(remove_blank_text=False)
            tree = etree.parse(pom_file, parser)
            root = tree.getroot()
            if pom_file == domain_pom:
                version_element = root.find("mvn:version", namespaces=namespace)
            else:
                version_element = root.find("mvn:parent/mvn:version", namespaces=namespace)
            if version_element is None:
                raise ValueError(f"缺少服务域版本节点: {pom_file}")
            version_element.text = new_version
            tree.write(
                pom_file, encoding="utf-8", xml_declaration=True, pretty_print=True
            )
        console_output_widget.append(
            f"[SUCCESS] 服务域 {target.domain_module} 已统一更新为: {new_version}"
        )

    @staticmethod
    def release_to_maven_and_switch_version(
        console_output_widget, sub_project_dir, command_executor=None
    ):
        """
        发布到 Maven 远程仓库并切换版本号
        """
        project_root_dir = ConfigManager.get_config_value(CommonDict.PROJECT_ROOT_DIR)
        target = ReleaseTargetResolver.resolve(project_root_dir, sub_project_dir)
        current_version = target.version

        if "SNAPSHOT" in current_version:
            release_version = current_version.replace("-SNAPSHOT", "-RELEASE")
            console_output_widget.append(f"准备发布版本: {release_version}")
            MavenHandler.update_release_target_version(
                console_output_widget, project_root_dir, target, release_version
            )
            try:
                console_output_widget.append(
                    f"[INFO] 正在将服务域发布到 Maven 远程仓库: {target.domain_module} ..."
                )
                MavenHandler.release_maven_project(
                    console_output_widget, target.domain_module, command_executor
                )
            except Exception:
                MavenHandler.update_release_target_version(
                    console_output_widget, project_root_dir, target, current_version
                )
                console_output_widget.append("[ERROR] Maven 发布失败，已恢复服务域 SNAPSHOT 版本。")
                raise

            new_version = MavenHandler._increase_version(current_version, "patch")
            MavenHandler.update_release_target_version(
                console_output_widget, project_root_dir, target, new_version
            )
            console_output_widget.append(
                f"[INFO] 发布完成，切换到下一个 SNAPSHOT 版本: {new_version}"
            )
        else:
            console_output_widget.append(
                "[ERROR] 当前版本已经是 RELEASE 版本，无法执行发布操作"
            )

    @staticmethod
    def update_pom_version(console_output_widget, pom_file, new_version):
        """
        更新 pom.xml 中的版本号，并保留注释
        """
        parser = etree.XMLParser(remove_blank_text=False)
        tree = etree.parse(pom_file, parser)
        root = tree.getroot()
        namespace = {"mvn": "http://maven.apache.org/POM/4.0.0"}

        version_element = root.find("mvn:version", namespaces=namespace)
        properties_element = root.find("mvn:properties", namespaces=namespace)

        if version_element is None:
            parent_pom_file = MavenHandler._get_parent_pom_file(
                pom_file, root, namespace
            )
            if parent_pom_file:
                console_output_widget.append(
                    "[INFO] 当前模块未显式声明版本号，将更新父 POM 版本。"
                )
                MavenHandler.update_pom_version(
                    console_output_widget, parent_pom_file, new_version
                )
                return

        if version_element is not None and version_element.text == "${revision}":
            if properties_element is not None:
                revision_element = properties_element.find(
                    "mvn:revision", namespaces=namespace
                )
                if revision_element is not None:
                    revision_element.text = new_version
                    console_output_widget.append(
                        f"[SUCCESS] 已将 revision 更新为: {new_version}"
                    )
                    tree.write(
                        pom_file,
                        encoding="utf-8",
                        xml_declaration=True,
                        pretty_print=True,
                    )
                    return
            parent_pom_file = MavenHandler._get_parent_pom_file(
                pom_file, root, namespace
            )
            if parent_pom_file:
                console_output_widget.append(
                    "[INFO] 当前模块通过 revision 继承版本号，将更新父 POM 版本。"
                )
                MavenHandler.update_pom_version(
                    console_output_widget, parent_pom_file, new_version
                )
                return

        if version_element is not None:
            version_element.text = new_version
            console_output_widget.append(f"[SUCCESS] 已将版本更新为: {new_version}")
            tree.write(
                pom_file, encoding="utf-8", xml_declaration=True, pretty_print=True
            )
            return

        console_output_widget.append(
            "[ERROR] 未找到可更新的版本号位置，无法更新版本号"
        )

    @staticmethod
    def increase_major_version(console_output_widget, pom_file):
        """
        提升主版本号
        """
        artifact_id, current_version = MavenHandler.get_artifact_and_version(pom_file)
        new_version = MavenHandler._increase_version(
            current_version, version_type="major"
        )
        MavenHandler.update_pom_version(console_output_widget, pom_file, new_version)

    @staticmethod
    def increase_minor_version(console_output_widget, pom_file):
        """
        提升次版本号
        """
        artifact_id, current_version = MavenHandler.get_artifact_and_version(pom_file)
        new_version = MavenHandler._increase_version(
            current_version, version_type="minor"
        )
        MavenHandler.update_pom_version(console_output_widget, pom_file, new_version)

    @staticmethod
    def increase_patch_version(console_output_widget, pom_file):
        """
        提升补丁版本号
        """
        artifact_id, current_version = MavenHandler.get_artifact_and_version(pom_file)
        new_version = MavenHandler._increase_version(
            current_version, version_type="patch"
        )
        MavenHandler.update_pom_version(console_output_widget, pom_file, new_version)
        return new_version

    @staticmethod
    def _increase_version(version, version_type="patch"):
        """
        优化版本号处理逻辑
        """
        try:
            # 处理 SNAPSHOT 和 RELEASE 后缀
            is_snapshot = "-SNAPSHOT" in version
            version = version.replace("-SNAPSHOT", "").replace("-RELEASE", "")

            # 分割版本号
            version_parts = version.split(".")
            if len(version_parts) != 3:
                raise ValueError(f"无效的版本号格式: {version}")

            # 转换为整数
            version_parts = [int(part) for part in version_parts]

            # 根据类型更新版本号
            if version_type == "major":
                version_parts[0] += 1
                version_parts[1] = version_parts[2] = 0
            elif version_type == "minor":
                version_parts[1] += 1
                version_parts[2] = 0
            elif version_type == "patch":
                version_parts[2] += 1
            else:
                raise ValueError(f"无效的版本类型: {version_type}")

            # 重新组装版本号
            new_version = ".".join(map(str, version_parts))
            return f"{new_version}-SNAPSHOT"

        except Exception as e:
            raise ValueError(f"版本号处理失败: {str(e)}")
