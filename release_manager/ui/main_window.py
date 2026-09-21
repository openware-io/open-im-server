import os
from PySide6.QtCore import QObject, QThread, Signal, Qt
from PySide6.QtGui import QColor
from PySide6.QtWidgets import (
    QMainWindow,
    QVBoxLayout,
    QWidget,
    QPushButton,
    QTextEdit,
    QHBoxLayout,
    QFileDialog,
    QLabel,
    QRadioButton,
    QCheckBox,
    QComboBox,
    QToolTip,
    QGroupBox,
    QProgressDialog,
)
from ui.config_window import ConfigWindow
from ui.custom_widgets import SearchableListWidget
from ui.project_operations import ProjectOperations
from ui.utilities import CommandExecutor
from utils.common_dict import CommonDict
from utils.config_manager import ConfigManager


class ConsoleProxy(QObject):
    message = Signal(str)

    def append(self, text):
        self.message.emit(str(text))


class WorkerThread(QThread):
    finished = Signal()
    error = Signal(str)

    def __init__(self, task_func, *args, **kwargs):
        super().__init__()
        self.task_func = task_func
        self.args = args
        self.kwargs = kwargs
        self._is_cancelled = False
        self.command_executor = CommandExecutor()

    def run(self):
        try:
            if not self._is_cancelled:
                if "command_executor" not in self.kwargs:
                    self.kwargs["command_executor"] = self.command_executor
                self.task_func(*self.args, **self.kwargs)
                if not self._is_cancelled:
                    self.finished.emit()
        except Exception as e:
            if not self._is_cancelled:
                self.error.emit(str(e))

    def cancel(self):
        """取消操作"""
        self._is_cancelled = True
        if hasattr(self, "command_executor"):
            self.command_executor.cancel()


class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        ConfigManager.init_config()
        self._is_loading_defaults = False
        self._is_syncing_docker_image = False
        self._manual_docker_image_override = False
        self.setWindowTitle("发版管理器")
        self.setGeometry(300, 300, 900, 700)
        self.setMouseTracking(True)

        # 初始化所有控件
        self.init_controls()

        central_widget = QWidget()
        self.setCentralWidget(central_widget)

        main_layout = QVBoxLayout()
        main_layout.setSpacing(10)
        main_layout.setContentsMargins(20, 20, 20, 20)
        central_widget.setLayout(main_layout)

        # 配置按钮域
        config_layout = QHBoxLayout()
        config_layout.setContentsMargins(0, 0, 0, 10)
        self.config_button = QPushButton("配置管理")
        self.config_button.setMinimumHeight(30)
        self.config_button.clicked.connect(self.open_config_window)
        config_layout.addWidget(self.config_button)
        config_layout.addStretch()
        main_layout.addLayout(config_layout)

        # 子项目列表区域
        project_group = QGroupBox()
        project_layout = QVBoxLayout()
        project_layout.setSpacing(5)
        project_group.setLayout(project_layout)

        subproject_label = QLabel("子项目列表 (输入任意文本可搜索)")
        subproject_label.setStyleSheet(
            """
            QLabel {
                color: #FFA500;
                font-size: 14px;
                font-weight: bold;
                padding: 5px;
            }
        """
        )
        project_layout.addWidget(subproject_label)

        self.current_project_label = QLabel()
        self.current_project_label.setWordWrap(True)
        self.current_project_label.setTextInteractionFlags(Qt.TextSelectableByMouse)
        self.current_project_label.setStyleSheet(
            """
            QLabel {
                color: #666666;
                font-size: 12px;
                padding-left: 6px;
                padding-right: 6px;
                padding-bottom: 4px;
            }
        """
        )
        project_layout.addWidget(self.current_project_label)

        self.subproject_list = SearchableListWidget()
        self.subproject_list.setMinimumHeight(250)
        project_layout.addWidget(self.subproject_list)

        # 加载和刷新按钮
        load_refresh_layout = QHBoxLayout()
        load_refresh_layout.setSpacing(10)

        self.load_button = QPushButton("加载子项目列表")
        self.load_button.setMinimumHeight(30)
        self.load_button.clicked.connect(self.load_project)

        self.refresh_button = QPushButton("刷新子项目列表")
        self.refresh_button.setMinimumHeight(30)
        self.refresh_button.clicked.connect(self.refresh_subproject_list)

        load_refresh_layout.addWidget(self.load_button)
        load_refresh_layout.addWidget(self.refresh_button)
        project_layout.addLayout(load_refresh_layout)

        main_layout.addWidget(project_group)

        # 版本控制区域
        version_group = QGroupBox("版本控制")
        version_layout = QHBoxLayout()
        version_layout.setSpacing(10)
        version_group.setLayout(version_layout)

        self.major_version_button = QPushButton("提升主版本号\n（不兼容变更）")
        self.minor_version_button = QPushButton("提升次版本号\n（兼容新功能）")
        self.patch_version_button = QPushButton("提升补丁版本号\n（Bug 修复）")

        for btn in [
            self.major_version_button,
            self.minor_version_button,
            self.patch_version_button,
        ]:
            btn.setMinimumHeight(50)
            version_layout.addWidget(btn)

        self.major_version_button.clicked.connect(
            lambda: self.increase_version("major")
        )
        self.minor_version_button.clicked.connect(
            lambda: self.increase_version("minor")
        )
        self.patch_version_button.clicked.connect(
            lambda: self.increase_version("patch")
        )

        #main_layout.addWidget(version_group)

        # 环境配置区域
        environment_group = QGroupBox("环境设置")
        environment_layout = QVBoxLayout()
        environment_layout.setSpacing(10)
        environment_group.setLayout(environment_layout)

        # 环境选择按钮组
        radio_layout = QHBoxLayout()
        radio_layout.setSpacing(20)

        for radio in [
            self.dev_radio,
            self.test_radio,
            self.cn_prod_radio,
            self.na_prod_radio,
            self.eu_prod_radio,
        ]:
            radio_layout.addWidget(radio)

        #environment_layout.addLayout(radio_layout)

        # 监控选项
        monitoring_layout = QHBoxLayout()
        monitoring_layout.setSpacing(20)
        monitoring_layout.addWidget(self.otel_checkbox)
        monitoring_layout.addWidget(self.arthas_checkbox)
        environment_layout.addLayout(monitoring_layout)

        # Docker基础镜像选择
        cloud_vendor_layout = QHBoxLayout()
        cloud_vendor_layout.addWidget(self.cloud_vendor_select_label)
        cloud_vendor_layout.addWidget(self.cloud_vendor_combo)
        environment_layout.addLayout(cloud_vendor_layout)

        java_distribution_layout = QHBoxLayout()
        java_distribution_layout.addWidget(self.java_distribution_select_label)
        java_distribution_layout.addWidget(self.java_distribution_combo)
        environment_layout.addLayout(java_distribution_layout)

        java_version_layout = QHBoxLayout()
        java_version_layout.addWidget(self.java_version_select_label)
        java_version_layout.addWidget(self.java_version_combo)
        environment_layout.addLayout(java_version_layout)

        docker_image_layout = QHBoxLayout()
        docker_image_layout.addWidget(self.docker_base_image_selec_label)
        docker_image_layout.addWidget(self.docker_base_image_combo)
        environment_layout.addLayout(docker_image_layout)

        main_layout.addWidget(environment_group)

        # 日志输出区域
        console_group = QGroupBox()
        console_layout = QVBoxLayout()
        console_layout.setSpacing(5)
        console_group.setLayout(console_layout)

        console_label = QLabel("操作日志")
        console_label.setStyleSheet(
            """
            QLabel {
                color: #FFA500;
                font-size: 14px;
                font-weight: bold;
                padding: 5px;
            }
        """
        )
        console_layout.addWidget(console_label)

        self.console_output = QTextEdit()
        self.console_output.setMinimumHeight(150)
        self.console_output.setReadOnly(True)
        self.console_output.textChanged.connect(self.auto_scroll_console)
        console_layout.addWidget(self.console_output)

        self.console_proxy = ConsoleProxy()
        self.console_proxy.message.connect(self.console_output.append, Qt.QueuedConnection)

        main_layout.addWidget(console_group)

        # 操作按钮区域
        actions_group = QGroupBox("发布操作")
        actions_layout = QVBoxLayout()
        actions_layout.setSpacing(10)
        actions_group.setLayout(actions_layout)

        # Docker操作按钮
        docker_layout = QHBoxLayout()
        docker_layout.setSpacing(10)
        docker_layout.addWidget(self.docker_package_button)
        #docker_layout.addWidget(self.docker_release_button)
        actions_layout.addLayout(docker_layout)

        # Maven操作按钮
        maven_layout = QHBoxLayout()
        maven_layout.setSpacing(10)
        maven_layout.addWidget(self.maven_package_button)
        #maven_layout.addWidget(self.maven_release_button)
        actions_layout.addLayout(maven_layout)

        main_layout.addWidget(actions_group)

        # 初始化项目操作对象
        self.project_operations = ProjectOperations(
            self.subproject_list, self.console_proxy
        )
        self.update_current_project_display(self.project_operations.root_dir)

        # 添加进度对话框
        self.progress_dialog = QProgressDialog(self)
        self.progress_dialog.setWindowTitle("请稍候")
        self.progress_dialog.setLabelText("正在处理...")
        self.progress_dialog.setMinimumDuration(1000)
        self.progress_dialog.setAutoClose(True)
        self.progress_dialog.setAutoReset(True)
        self.progress_dialog.setWindowModality(Qt.WindowModal)
        # 设置取消按钮
        cancel_button = QPushButton("取消")
        self.progress_dialog.setCancelButton(cancel_button)
        self.progress_dialog.canceled.connect(self.cancel_operation)
        self.progress_dialog.reset()
        self.progress_dialog.hide()

    def init_controls(self):
        """初始化所有控件"""
        # 初始化环境选择单选按钮
        self.dev_radio = QRadioButton("开发环境")
        self.test_radio = QRadioButton("测试环境")
        self.cn_prod_radio = QRadioButton("大中华生产")
        self.na_prod_radio = QRadioButton("北美生产")
        self.eu_prod_radio = QRadioButton("欧洲生产")

        # 设置单选按钮提示
        self.dev_radio.setToolTip("目前有一个影响: 是否可以发布到生产环境")
        self.test_radio.setToolTip("目前只有一个影响: 是否可以发布到生产环境")
        self.cn_prod_radio.setToolTip("目前只有一个影响: 是否可以发布到生产环境")
        self.na_prod_radio.setToolTip("目前只有一个影响: 是否可以发布到生产环境")
        self.eu_prod_radio.setToolTip("目前只有一个影响: 是否可以发布到生产环境")

        # 设置生产环境按钮的警告颜色
        warning_text_color = QColor(255, 165, 0)
        for radio in [self.cn_prod_radio, self.na_prod_radio, self.eu_prod_radio]:
            warning_palette = radio.palette()
            warning_palette.setColor(radio.foregroundRole(), warning_text_color)
            radio.setPalette(warning_palette)

        # 连接环境选择信号
        self.dev_radio.toggled.connect(
            lambda: ConfigManager.set_config_value(CommonDict.PKG_ENV, "dev")
        )
        self.test_radio.toggled.connect(
            lambda: ConfigManager.set_config_value(CommonDict.PKG_ENV, "test")
        )
        self.cn_prod_radio.toggled.connect(
            lambda: ConfigManager.set_config_value(CommonDict.PKG_ENV, "prod")
        )
        self.na_prod_radio.toggled.connect(
            lambda: ConfigManager.set_config_value(CommonDict.PKG_ENV, "prod_na")
        )
        self.eu_prod_radio.toggled.connect(
            lambda: ConfigManager.set_config_value(CommonDict.PKG_ENV, "prod_eu")
        )

        # 设置默认选中的环境
        pkg_environment = ConfigManager.get_config_value(CommonDict.PKG_ENV, None)
        if pkg_environment:
            if pkg_environment == "dev":
                self.dev_radio.setChecked(True)
            elif pkg_environment == "test":
                self.test_radio.setChecked(True)
            elif pkg_environment == "prod":
                self.cn_prod_radio.setChecked(True)
            elif pkg_environment == "prod_na":
                self.na_prod_radio.setChecked(True)
            elif pkg_environment == "prod_eu":
                self.eu_prod_radio.setChecked(True)
        else:
            self.dev_radio.setChecked(True)

        # 初始化监控选项复选框
        self.otel_checkbox = QCheckBox("接入 OTEL性能监控")
        self.otel_checkbox.setEnabled(False)

        self.arthas_checkbox = QCheckBox("接入 Arthas")

        self.otel_checkbox.setToolTip("已支持阿里云的OpenTelemetry 链路追踪")
        self.arthas_checkbox.setToolTip(
            "Arthas 是一款线上监控诊断产品，通过全局视角实时查看应用 load、内存、gc、线程的状态信息，并能在不修改应用代码的情况下，对业务问题进行诊断，"
            "包括查看方法调用的出入参、异常，监测方法执行耗时，类加载信息等，大大提升线上问题排查效率。"
        )

        # 连接监控选项信号
        self.otel_checkbox.toggled.connect(
            lambda: ConfigManager.set_config_value(
                CommonDict.OTEL_MONITORING, self.otel_checkbox.isChecked()
            )
        )
        self.arthas_checkbox.toggled.connect(
            lambda: ConfigManager.set_config_value(
                CommonDict.ARTHAS, self.arthas_checkbox.isChecked()
            )
        )

        # 设置监控选项的默认值
        self.otel_checkbox.setChecked(
            ConfigManager.get_config_value(CommonDict.OTEL_MONITORING, False)
        )
        self.arthas_checkbox.setChecked(
            ConfigManager.get_config_value(CommonDict.ARTHAS, False)
        )

        self.cloud_vendor_select_label = QLabel("云厂商预设")
        self.cloud_vendor_combo = QComboBox()
        for cloud_vendor, label in CommonDict.CLOUD_VENDORS.items():
            self.cloud_vendor_combo.addItem(label, cloud_vendor)

        selected_cloud_vendor = str(
            ConfigManager.get_config_value(
                CommonDict.PREFERRED_CLOUD_VENDOR,
                ConfigManager.get_default_cloud_vendor(),
            )
        )
        cloud_vendor_index = self.cloud_vendor_combo.findData(selected_cloud_vendor)
        if cloud_vendor_index >= 0:
            self.cloud_vendor_combo.setCurrentIndex(cloud_vendor_index)

        # 初始化JDK发行版
        self.java_distribution_select_label = QLabel("JDK 发行版")
        self.java_distribution_combo = QComboBox()
        for java_distribution, label in CommonDict.JAVA_DISTRIBUTIONS.items():
            self.java_distribution_combo.addItem(label, java_distribution)

        selected_java_distribution = str(
            ConfigManager.get_config_value(
                CommonDict.PREFERRED_JAVA_DISTRIBUTION,
                ConfigManager.get_default_java_distribution(selected_cloud_vendor),
            )
        )
        java_distribution_index = self.java_distribution_combo.findData(
            selected_java_distribution
        )
        if java_distribution_index >= 0:
            self.java_distribution_combo.setCurrentIndex(java_distribution_index)

        # 初始化 Java 版本选择
        self.java_version_select_label = QLabel("Java 版本选择")
        self.java_version_combo = QComboBox()
        for java_version, label in CommonDict.JAVA_LTS_VERSIONS.items():
            self.java_version_combo.addItem(label, java_version)

        selected_java_version = str(
            ConfigManager.get_config_value(
                CommonDict.PREFERRED_JAVA_VERSION,
                ConfigManager.get_default_java_version(),
            )
        )
        java_index = self.java_version_combo.findData(selected_java_version)
        if java_index >= 0:
            self.java_version_combo.setCurrentIndex(java_index)

        # 初始化Docker基础镜像选择
        self.docker_base_image_selec_label = QLabel("基础镜像版本选择")
        self.docker_base_image_combo = QComboBox()

        # 设置默认选中的Docker基础镜像版本
        selected_version = ConfigManager.get_config_value(
            CommonDict.DOCKER_BASE_IMAGE_VERSION,
            ConfigManager.get_default_docker_base_image_version(
                selected_java_version,
                selected_java_distribution,
            ),
        )
        self.refresh_docker_base_image_options(
            selected_java_distribution,
            selected_java_version,
            selected_version,
        )
        self._manual_docker_image_override = (
            self.docker_base_image_combo.currentText()
            != ConfigManager.get_default_docker_base_image_version(
                selected_java_version,
                selected_java_distribution,
            )
        )

        # 初始化操作按钮
        self.docker_package_button = QPushButton("打包到 Docker 镜像仓库")
        self.docker_package_button.setMinimumHeight(50)
        self.docker_release_button = QPushButton(
            "[谨慎操作] 发布到 Docker 镜像仓库并切换到 SNAPSHOT 版本"
        )
        self.docker_release_button.setStyleSheet("color: orange;")

        self.maven_package_button = QPushButton("打包到 Maven 远程仓库")
        self.maven_package_button.setMinimumHeight(50)
        self.maven_release_button = QPushButton(
            "[谨慎操作] 发布到 Maven 程仓库并切换到 SNAPSHOT 版本"
        )
        self.maven_release_button.setStyleSheet("color: orange;")

        # 连接按钮点击事件
        self.docker_package_button.clicked.connect(self.package_to_docker)
        self.docker_release_button.clicked.connect(self.release_to_docker_snapshot)
        self.maven_package_button.clicked.connect(self.package_to_maven)
        self.maven_release_button.clicked.connect(self.release_to_maven_snapshot)

        # 连接Docker基础镜像选择的变更事件
        self.docker_base_image_combo.currentIndexChanged.connect(
            self.save_docker_base_image_version
        )
        self.docker_base_image_combo.currentIndexChanged.connect(
            self.handle_docker_base_image_changed
        )
        self.cloud_vendor_combo.currentIndexChanged.connect(
            self.save_preferred_cloud_vendor
        )
        self.java_distribution_combo.currentIndexChanged.connect(
            self.save_preferred_java_distribution
        )
        self.java_version_combo.currentIndexChanged.connect(
            self.save_preferred_java_version
        )

    def enterEvent(self, event):
        QToolTip.showText(event.globalPos(), self.toolTip())

    def save_docker_base_image_version(self, index):
        selected_version = self.docker_base_image_combo.itemText(index)
        ConfigManager.set_config_value(
            CommonDict.DOCKER_BASE_IMAGE_VERSION, selected_version
        )

    def refresh_docker_base_image_options(
        self,
        java_distribution,
        java_version,
        selected_image=None,
    ):
        candidate_images = ConfigManager.get_docker_base_image_versions(
            java_distribution,
            java_version,
        )
        default_image = ConfigManager.get_default_docker_base_image_version(
            java_version,
            java_distribution,
        )
        target_image = (
            selected_image if selected_image in candidate_images else default_image
        )

        self._is_syncing_docker_image = True
        self.docker_base_image_combo.clear()
        for image_version in candidate_images:
            self.docker_base_image_combo.addItem(image_version)

        docker_index = self.docker_base_image_combo.findText(target_image)
        if docker_index >= 0:
            self.docker_base_image_combo.setCurrentIndex(docker_index)
        self._is_syncing_docker_image = False

    def handle_docker_base_image_changed(self, *_args):
        if self._is_loading_defaults or self._is_syncing_docker_image:
            return

        preferred_java_distribution = self.java_distribution_combo.currentData()
        expected_default_image = ConfigManager.get_default_docker_base_image_version(
            self.java_version_combo.currentData(),
            preferred_java_distribution,
        )
        self._manual_docker_image_override = (
            self.docker_base_image_combo.currentText() != expected_default_image
        )

    def save_preferred_cloud_vendor(self, index):
        selected_cloud_vendor = self.cloud_vendor_combo.itemData(index)
        if selected_cloud_vendor is None:
            return

        selected_cloud_vendor = str(selected_cloud_vendor)
        ConfigManager.set_config_value(
            CommonDict.PREFERRED_CLOUD_VENDOR, selected_cloud_vendor
        )
        default_distribution = ConfigManager.get_default_java_distribution(
            selected_cloud_vendor
        )
        distribution_index = self.java_distribution_combo.findData(default_distribution)
        if distribution_index >= 0:
            self.java_distribution_combo.setCurrentIndex(distribution_index)

    def save_preferred_java_distribution(self, index):
        selected_java_distribution = self.java_distribution_combo.itemData(index)
        if selected_java_distribution is None:
            return

        ConfigManager.set_config_value(
            CommonDict.PREFERRED_JAVA_DISTRIBUTION, str(selected_java_distribution)
        )
        self.sync_java_preferences()

    def save_preferred_java_version(self, index):
        selected_java_version = self.java_version_combo.itemData(index)
        if selected_java_version is None:
            return

        selected_java_version = str(selected_java_version)
        ConfigManager.set_config_value(
            CommonDict.PREFERRED_JAVA_VERSION, selected_java_version
        )
        self.sync_java_preferences()

    def sync_java_preferences(self):
        selected_java_distribution = self.java_distribution_combo.currentData()
        selected_java_version = self.java_version_combo.currentData()
        if selected_java_distribution is None or selected_java_version is None:
            return

        selected_image = None
        if self._manual_docker_image_override:
            current_image = self.docker_base_image_combo.currentText()
            if current_image in ConfigManager.get_docker_base_image_versions(
                selected_java_distribution,
                selected_java_version,
            ):
                selected_image = current_image

        self.refresh_docker_base_image_options(
            selected_java_distribution,
            selected_java_version,
            selected_image,
        )
        self._manual_docker_image_override = (
            self.docker_base_image_combo.currentText()
            != ConfigManager.get_default_docker_base_image_version(
                selected_java_version,
                selected_java_distribution,
            )
        )

    def update_current_project_display(self, dir_path):
        if dir_path:
            project_name = os.path.basename(os.path.normpath(dir_path))
            self.current_project_label.setText(
                f"当前已加载项目: {project_name}\n项目路径: {dir_path}"
            )
            self.current_project_label.setToolTip(dir_path)
            self.setWindowTitle(f"发版管理器 - {project_name}")
        else:
            self.current_project_label.setText("当前已加载项目: 未选择")
            self.current_project_label.setToolTip("")
            self.setWindowTitle("发版管理器")

    def open_config_window(self):
        config_window = ConfigWindow()
        if config_window.exec_():
            self.reload_config_defaults()

    def reload_config_defaults(self):
        self._is_loading_defaults = True
        selected_cloud_vendor = str(
            ConfigManager.get_config_value(
                CommonDict.PREFERRED_CLOUD_VENDOR,
                ConfigManager.get_default_cloud_vendor(),
            )
        )
        cloud_vendor_index = self.cloud_vendor_combo.findData(selected_cloud_vendor)
        if cloud_vendor_index >= 0:
            self.cloud_vendor_combo.setCurrentIndex(cloud_vendor_index)

        selected_java_distribution = str(
            ConfigManager.get_config_value(
                CommonDict.PREFERRED_JAVA_DISTRIBUTION,
                ConfigManager.get_default_java_distribution(selected_cloud_vendor),
            )
        )
        java_distribution_index = self.java_distribution_combo.findData(
            selected_java_distribution
        )
        if java_distribution_index >= 0:
            self.java_distribution_combo.setCurrentIndex(java_distribution_index)

        selected_java_version = str(
            ConfigManager.get_config_value(
                CommonDict.PREFERRED_JAVA_VERSION,
                ConfigManager.get_default_java_version(),
            )
        )
        java_index = self.java_version_combo.findData(selected_java_version)
        if java_index >= 0:
            self.java_version_combo.setCurrentIndex(java_index)

        docker_base_image_version = ConfigManager.get_config_value(
            CommonDict.DOCKER_BASE_IMAGE_VERSION,
            ConfigManager.get_default_docker_base_image_version(
                selected_java_version,
                selected_java_distribution,
            ),
        )
        self.refresh_docker_base_image_options(
            selected_java_distribution,
            selected_java_version,
            docker_base_image_version,
        )
        self._manual_docker_image_override = (
            self.docker_base_image_combo.currentText()
            != ConfigManager.get_default_docker_base_image_version(
                selected_java_version,
                selected_java_distribution,
            )
        )
        self._is_loading_defaults = False

    def load_project(self):
        dir_path = QFileDialog.getExistingDirectory(self, "选择项目根目录")
        if dir_path:
            ConfigManager.set_config_value(CommonDict.PROJECT_ROOT_DIR, dir_path)
            self.project_operations.load_subprojects(dir_path)
            self.update_current_project_display(dir_path)
        else:
            self.console_output.append("[ERROR] 未选择任何项目目录")

    def refresh_subproject_list(self):
        self.project_operations.refresh_subprojects()
        self.update_current_project_display(self.project_operations.root_dir)

    def increase_version(self, version_type):
        """
        提升版本号
        """
        try:
            selected_project = self.project_operations.prepare_selected_subproject()
            if not selected_project:
                return
            self.execute_with_progress(
                self.project_operations.increase_version, version_type, selected_project
            )
        except Exception as e:
            self.console_output.append(f"[ERROR] 版本号更新失败: {str(e)}")

    def execute_with_progress(self, task_func, *args, **kwargs):
        """使用进度对话框执行耗时任务"""
        self.progress_dialog.reset()
        self.progress_dialog.setWindowModality(Qt.WindowModal)
        self.progress_dialog.show()

        # 创建工作线程
        self.worker = WorkerThread(task_func, *args, **kwargs)

        # 连接信号
        self.worker.finished.connect(self.on_task_finished)
        self.worker.error.connect(self.on_task_error)

        # 启动线程
        self.worker.start()

    def cancel_operation(self):
        """取消当前操作"""
        if hasattr(self, "worker") and self.worker.isRunning():
            self.console_output.append("[INFO] 正在取消操作...")
            self.worker.cancel()
            self.progress_dialog.close()

    def on_task_finished(self):
        """任务完成时的处理"""
        if hasattr(self, "worker") and not self.worker._is_cancelled:
            self.progress_dialog.close()
            self.worker.deleteLater()
            self.console_output.append("[SUCCESS] 操作已完成")

    def on_task_error(self, error_msg):
        """任务出错时的处理"""
        if hasattr(self, "worker") and not self.worker._is_cancelled:
            self.progress_dialog.close()
            self.worker.deleteLater()
            self.console_output.append(f"[ERROR] {error_msg}")

    def package_to_docker(self):
        try:
            selected_project = self.project_operations.prepare_selected_subproject()
            if not selected_project:
                return
            self.execute_with_progress(
                self.project_operations.execute_task,
                "docker_package",
                selected_project,
            )
        except Exception as e:
            self.console_output.append(f"[ERROR] 打包到Docker失败: {str(e)}")

    def release_to_docker_snapshot(self):
        try:
            if self.dev_radio.isChecked() or self.test_radio.isChecked():
                self.console_output.append("[ERROR] 请选择生产环境")
                return

            self.console_output.append("[INFO] 发布到 Docker 并切换到 SNAPSHOT 版本...")
            selected_project = self.project_operations.prepare_selected_subproject()
            if not selected_project:
                return
            self.execute_with_progress(
                self.project_operations.execute_task,
                "docker_release_snapshot",
                selected_project,
            )
        except Exception as e:
            self.console_output.append(f"[ERROR] 发布到Docker失败: {str(e)}")

    def package_to_maven(self):
        try:
            selected_project = self.project_operations.prepare_selected_subproject()
            if not selected_project:
                return
            self.execute_with_progress(
                self.project_operations.execute_task,
                "maven_package",
                selected_project,
            )
        except Exception as e:
            self.console_output.append(f"[ERROR] 打包到Maven失败: {str(e)}")

    def release_to_maven_snapshot(self):
        try:
            if self.dev_radio.isChecked() or self.test_radio.isChecked():
                self.console_output.append("[ERROR] 请选择生产环境")
                return

            self.console_output.append("[INFO] 发布到 Maven 并切换到 SNAPSHOT 版本...")
            selected_project = self.project_operations.prepare_selected_subproject()
            if not selected_project:
                return
            self.execute_with_progress(
                self.project_operations.execute_task,
                "maven_release_snapshot",
                selected_project,
            )
        except Exception as e:
            self.console_output.append(f"[ERROR] 发布到Maven失败: {str(e)}")

    def auto_scroll_console(self):
        """自动滚动日志输出框到最新内容"""
        scrollbar = self.console_output.verticalScrollBar()
        scrollbar.setValue(scrollbar.maximum())
