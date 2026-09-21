import os
from PySide6.QtCore import Qt
from PySide6.QtWidgets import (
    QListWidget,
    QListWidgetItem,
)
from core.docker_handler import DockerHandler
from core.maven_handler import MavenHandler
from core.release_target import ReleaseTargetResolver
from utils.common_dict import CommonDict
from utils.config_manager import ConfigManager


class ProjectOperations:
    def __init__(self, subproject_list_widget: QListWidget, console_output_widget):
        self.subproject_list_widget = subproject_list_widget
        self.console_output_widget = console_output_widget
        self.subprojects = []
        self.root_dir = ConfigManager.get_config_value(
            CommonDict.PROJECT_ROOT_DIR, None
        )

        # 初始化操作次数字典
        self.subproject_operation_count = {}
        # 从配置中加载操作次数
        self.load_operation_count()

        # 如果项目根目录不存在，则清空配置并设置为空
        if self.root_dir is None or not os.path.exists(self.root_dir):
            self.root_dir = None
            ConfigManager.set_config_value(CommonDict.PROJECT_ROOT_DIR, "")

        if self.root_dir:
            self.refresh_subprojects()

    def load_operation_count(self):
        config_manager = ConfigManager()
        self.subproject_operation_count = config_manager.get_config_value(
            CommonDict.SUB_PROJECT_OPERATION_COUNT, {}
        )

    def save_operation_count(self):
        config_manager = ConfigManager()
        config_manager.set_config_value(
            CommonDict.SUB_PROJECT_OPERATION_COUNT, self.subproject_operation_count
        )

    def init_subproject_operation_count(self, subprojects):
        current_subproject_names = {
            subproject["name"] for subproject in subprojects if "name" in subproject
        }
        if not current_subproject_names:
            self.subproject_operation_count.clear()
            self.save_operation_count()
            return

        # 切换项目根目录后，旧项目的统计数据可能仍残留在配置中。
        # 这里仅保留当前项目下的子项目统计，并为新子项目补零，避免排序时访问不存在的键。
        filtered_operation_count = {
            name: self.subproject_operation_count.get(name, 0)
            for name in current_subproject_names
        }

        if filtered_operation_count != self.subproject_operation_count:
            self.subproject_operation_count = filtered_operation_count
            self.save_operation_count()

    def increment_subproject_operation_count(self, subproject):
        if subproject in self.subproject_operation_count:
            self.subproject_operation_count[subproject] += 1
        else:
            self.subproject_operation_count[subproject] = 1
        self.save_operation_count()

    def load_subprojects(self, root_dir):
        self.console_output_widget.append("[INFO] 正在加载子项目...")
        # 确保使用绝对路径
        if not os.path.isabs(root_dir):
            root_dir = os.path.join(ConfigManager.get_project_root(), root_dir)

        self.root_dir = root_dir
        ConfigManager.set_config_value(CommonDict.PROJECT_ROOT_DIR, root_dir)

        self.subprojects = MavenHandler.find_sub_projects(
            self.console_output_widget, root_dir
        )
        if not self.subprojects:
            self.console_output_widget.append("[ERROR] 没有找到子项目。")
            return

        self.update_subproject_list()

    def refresh_subprojects(self):
        try:
            if self.root_dir:
                self.console_output_widget.append("[INFO] 正在刷新子项目列表...")
                self.subprojects = MavenHandler.find_sub_projects(
                    self.console_output_widget, self.root_dir
                )
                self.update_subproject_list()
            else:
                self.console_output_widget.append("[ERROR] 请先加载项目根目录。")
        except:
            ConfigManager.set_config_value(CommonDict.SUB_PROJECT_OPERATION_COUNT, {})
            self.refresh_subprojects()

    def update_subproject_list(self):

        tmp_subprojects = {}
        for subproject in self.subprojects:
            tmp_subprojects[subproject["name"]] = subproject["color"]

        # 初始化操作次数
        self.init_subproject_operation_count(self.subprojects)

        # 按照操作次数从大到小排序
        sorted_subprojects = sorted(
            self.subproject_operation_count.items(), key=lambda x: x[1], reverse=True
        )
        self.subprojects.clear()
        for subproject, count in sorted_subprojects:
            self.subprojects.append(
                {"name": subproject, "color": tmp_subprojects.get(subproject, "black")}
            )

        self.subproject_list_widget.clear()
        for subproject in self.subprojects:
            item = QListWidgetItem(subproject["name"])
            if subproject["color"] == "green":
                # 设置文本颜色为深绿色
                item.setForeground(Qt.darkGreen)
            self.subproject_list_widget.addItem(item)

    def prepare_selected_subproject(self):
        selected_items = self.subproject_list_widget.selectedItems()
        if not selected_items:
            self.console_output_widget.append("[ERROR] 请先选择一个子项目。")
            return None

        selected_project = selected_items[0].text()
        self.console_output_widget.append(f"[INFO] 选择的子项目: {selected_project}")
        self.increment_subproject_operation_count(selected_project)
        self.update_subproject_list()
        return selected_project

    def increase_version(self, version_type, selected_project, command_executor=None):
        """
        提升版本号

        Args:
            version_type: 版本类型 ('major', 'minor', 'patch')
            command_executor: 命令执行器(可选)
        """
        print(f"调试: {version_type}")
        if not selected_project:
            self.console_output_widget.append("[ERROR] 请先选择一个子项目。")
            return

        self.console_output_widget.append(f"[INFO] 开始提升版本号: {version_type}")
        pom_file = os.path.join(
            self.root_dir, selected_project.lstrip("/"), "pom.xml"
        )

        if version_type == "major":
            self.console_output_widget.append("[INFO] 提升主版本号...")
            MavenHandler.increase_major_version(self.console_output_widget, pom_file)
        elif version_type == "minor":
            self.console_output_widget.append("[INFO] 提升次版本号...")
            MavenHandler.increase_minor_version(self.console_output_widget, pom_file)
        elif version_type == "patch":
            self.console_output_widget.append("[INFO] 提升补丁版本号...")
            MavenHandler.increase_patch_version(self.console_output_widget, pom_file)
        self.console_output_widget.append("[SUCCESS] 版本号提升完成")

    def execute_task(self, task_type, selected_project, command_executor=None):
        if not selected_project:
            self.console_output_widget.append("[ERROR] 请先选择一个子项目。")
            return

        self.console_output_widget.append(
            f"[INFO] 当前项目目录: {selected_project} 正在执行任务: {task_type}..."
        )
        target = ReleaseTargetResolver.resolve(self.root_dir, selected_project)
        pom_file = os.path.join(self.root_dir, target.domain_module, "pom.xml")
        pkg_environment = ConfigManager.get_config_value(CommonDict.PKG_ENV, "dev")

        if task_type == "docker_package":
            MavenHandler.build_maven_project(
                self.console_output_widget, selected_project, command_executor
            )
            DockerHandler.build_and_push_docker_image(
                self.console_output_widget,
                target.artifact_id,
                target.version,
                self.root_dir,
                target.deploy_module,
                pkg_environment,
                command_executor,
            )
            self.console_output_widget.append("[SUCCESS] Docker打包完成")
        elif task_type == "docker_release_snapshot":
            current_version = target.version

            if not "SNAPSHOT" in current_version:
                self.console_output_widget.append(
                    "[ERROR] 当前版本已是RELEASE版本，无法发布"
                )
                return

            release_version = current_version.replace("-SNAPSHOT", "-RELEASE")
            self.console_output_widget.append(f"[INFO] 准备发布版本: {release_version}")
            MavenHandler.update_release_target_version(
                self.console_output_widget,
                self.root_dir,
                target,
                release_version,
            )
            try:
                target = ReleaseTargetResolver.resolve(self.root_dir, selected_project)
                MavenHandler.build_maven_project(
                    self.console_output_widget, selected_project, command_executor
                )
                DockerHandler.build_and_push_docker_image(
                    self.console_output_widget,
                    target.artifact_id,
                    target.version,
                    self.root_dir,
                    target.deploy_module,
                    pkg_environment,
                    command_executor,
                )
            except Exception:
                MavenHandler.update_release_target_version(
                    self.console_output_widget,
                    self.root_dir,
                    target,
                    current_version,
                )
                self.console_output_widget.append("[ERROR] Docker 发布失败，已恢复服务域 SNAPSHOT 版本。")
                raise
            new_version = MavenHandler._increase_version(current_version, "patch")
            MavenHandler.update_release_target_version(
                self.console_output_widget, self.root_dir, target, new_version
            )
            self.console_output_widget.append(
                f"[SUCCESS] 发布完成，切换到下一个 SNAPSHOT 版本: {new_version}"
            )
            self.console_output_widget.append("[SUCCESS] Docker发布并切换 SNAPSHOT 完成")
        elif task_type == "maven_package":
            MavenHandler.package_to_maven(
                self.console_output_widget, selected_project, command_executor
            )
            self.console_output_widget.append("[SUCCESS] 打包到 Maven 远程仓库完成")
        elif task_type == "maven_release_snapshot":
            MavenHandler.release_to_maven_and_switch_version(
                self.console_output_widget,
                selected_project,
                command_executor,
            )
            self.console_output_widget.append("[SUCCESS] 发布到 Maven 并切换 SNAPSHOT 完成")
