from PySide6.QtWidgets import (
    QDialog,
    QVBoxLayout,
    QFormLayout,
    QLineEdit,
    QPushButton,
    QLabel,
    QComboBox,
)
from utils.common_dict import CommonDict
from utils.config_manager import ConfigManager


class ConfigWindow(QDialog):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("配置管理")
        self.resize(800, 420)
        self.setMinimumWidth(800)
        self._is_loading_config = False
        self._is_syncing_default_docker_image = False
        self._manual_docker_image_override = False

        layout = QVBoxLayout()

        form_layout = QFormLayout()

        self.docker_registry_input = QLineEdit()
        self.docker_namespace_input = QLineEdit()
        self.docker_username_input = QLineEdit()
        self.docker_password_input = QLineEdit()
        self.docker_password_input.setEchoMode(QLineEdit.Password)
        self.preferred_cloud_vendor_combo = QComboBox()
        self.preferred_java_distribution_combo = QComboBox()
        self.preferred_java_version_combo = QComboBox()
        self.default_docker_base_image_combo = QComboBox()
        self.project_root_dir_label = QLabel()
        self.pkg_environment_label = QLabel()
        self.otel_monitoring_label = QLabel()
        self.arthas_label = QLabel()

        for cloud_vendor, label in CommonDict.CLOUD_VENDORS.items():
            self.preferred_cloud_vendor_combo.addItem(label, cloud_vendor)
        for distribution, label in CommonDict.JAVA_DISTRIBUTIONS.items():
            self.preferred_java_distribution_combo.addItem(label, distribution)
        for java_version, label in CommonDict.JAVA_LTS_VERSIONS.items():
            self.preferred_java_version_combo.addItem(label, java_version)

        form_layout.addRow("项目根目录", self.project_root_dir_label)
        form_layout.addRow("当前打包环境", self.pkg_environment_label)
        form_layout.addRow("云厂商预设", self.preferred_cloud_vendor_combo)
        form_layout.addRow("默认 JDK 发行版", self.preferred_java_distribution_combo)
        form_layout.addRow("默认 Java 版本", self.preferred_java_version_combo)
        form_layout.addRow("默认 Docker 基础镜像", self.default_docker_base_image_combo)
        form_layout.addRow("Docker Registry", self.docker_registry_input)
        form_layout.addRow("Docker Namespace", self.docker_namespace_input)
        form_layout.addRow("Docker 用户名", self.docker_username_input)
        form_layout.addRow("Docker 密码", self.docker_password_input)
        form_layout.addRow("接入OTEL性能监控", self.otel_monitoring_label)
        form_layout.addRow("接入Arthas", self.arthas_label)

        layout.addLayout(form_layout)

        self.save_button = QPushButton("保存")
        self.save_button.clicked.connect(self.save_config)
        layout.addWidget(self.save_button)

        self.setLayout(layout)

        self.preferred_cloud_vendor_combo.currentIndexChanged.connect(
            self.handle_cloud_vendor_changed
        )
        self.preferred_java_distribution_combo.currentIndexChanged.connect(
            self.handle_java_preferences_changed
        )
        self.preferred_java_version_combo.currentIndexChanged.connect(
            self.handle_java_preferences_changed
        )
        self.default_docker_base_image_combo.currentIndexChanged.connect(
            self.handle_default_docker_image_changed
        )

        # 加载当前配置
        self.load_config()

    def load_config(self):
        self._is_loading_config = True
        config = ConfigManager.load_config()
        self.docker_registry_input.setText(config.get(CommonDict.DOCKER_REGISTRY, ""))
        self.docker_namespace_input.setText(
            config.get(CommonDict.DOCKER_NAMESPACE, "")
        )
        self.docker_username_input.setText(
            config.get(CommonDict.DOCKER_USERNAME, "")
        )
        self.docker_password_input.setText(
            config.get(CommonDict.DOCKER_PASSWORD, "")
        )

        self.pkg_environment_label.setText(
            CommonDict.PKG_ENV_DICT[config.get(CommonDict.PKG_ENV, "dev")]
        )
        self.project_root_dir_label.setText(config.get(CommonDict.PROJECT_ROOT_DIR, ""))
        self.otel_monitoring_label.setText(
            str(config.get(CommonDict.OTEL_MONITORING, False))
        )
        self.arthas_label.setText(str(config.get(CommonDict.ARTHAS, False)))
        preferred_cloud_vendor = str(
            config.get(
                CommonDict.PREFERRED_CLOUD_VENDOR,
                ConfigManager.get_default_cloud_vendor(),
            )
        )
        cloud_index = self.preferred_cloud_vendor_combo.findData(preferred_cloud_vendor)
        if cloud_index >= 0:
            self.preferred_cloud_vendor_combo.setCurrentIndex(cloud_index)
        preferred_java_distribution = str(
            config.get(
                CommonDict.PREFERRED_JAVA_DISTRIBUTION,
                ConfigManager.get_default_java_distribution(preferred_cloud_vendor),
            )
        )
        distribution_index = self.preferred_java_distribution_combo.findData(
            preferred_java_distribution
        )
        if distribution_index >= 0:
            self.preferred_java_distribution_combo.setCurrentIndex(distribution_index)
        preferred_java_version = str(
            config.get(
                CommonDict.PREFERRED_JAVA_VERSION,
                ConfigManager.get_default_java_version(),
            )
        )
        java_index = self.preferred_java_version_combo.findData(preferred_java_version)
        if java_index >= 0:
            self.preferred_java_version_combo.setCurrentIndex(java_index)

        docker_base_image_version = config.get(
            CommonDict.DOCKER_BASE_IMAGE_VERSION,
            ConfigManager.get_default_docker_base_image_version(
                preferred_java_version,
                preferred_java_distribution,
            ),
        )
        self.refresh_docker_base_image_options(
            preferred_java_distribution,
            preferred_java_version,
            docker_base_image_version
        )
        self._manual_docker_image_override = (
            self.default_docker_base_image_combo.currentText()
            != ConfigManager.get_default_docker_base_image_version(
                preferred_java_version,
                preferred_java_distribution,
            )
        )
        self._is_loading_config = False

    def save_config(self):
        config = ConfigManager.load_config()
        config[CommonDict.DOCKER_REGISTRY] = self.docker_registry_input.text()
        config[CommonDict.DOCKER_NAMESPACE] = self.docker_namespace_input.text()
        config[CommonDict.DOCKER_USERNAME] = self.docker_username_input.text()
        config[CommonDict.DOCKER_PASSWORD] = self.docker_password_input.text()
        config[CommonDict.PREFERRED_CLOUD_VENDOR] = (
            self.preferred_cloud_vendor_combo.currentData()
        )
        config[CommonDict.PREFERRED_JAVA_DISTRIBUTION] = (
            self.preferred_java_distribution_combo.currentData()
        )
        config[CommonDict.PREFERRED_JAVA_VERSION] = (
            self.preferred_java_version_combo.currentData()
        )
        config[CommonDict.DOCKER_BASE_IMAGE_VERSION] = (
            self.default_docker_base_image_combo.currentText()
        )
        ConfigManager.save_config(config)
        self.accept()

    def handle_cloud_vendor_changed(self, *_args):
        if self._is_loading_config:
            return

        preferred_cloud_vendor = self.preferred_cloud_vendor_combo.currentData()
        if preferred_cloud_vendor is None:
            return

        default_distribution = ConfigManager.get_default_java_distribution(
            preferred_cloud_vendor
        )
        distribution_index = self.preferred_java_distribution_combo.findData(
            default_distribution
        )
        if distribution_index >= 0:
            self.preferred_java_distribution_combo.setCurrentIndex(distribution_index)

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

        self._is_syncing_default_docker_image = True
        self.default_docker_base_image_combo.clear()
        for image_version in candidate_images:
            self.default_docker_base_image_combo.addItem(image_version)

        docker_index = self.default_docker_base_image_combo.findText(target_image)
        if docker_index >= 0:
            self.default_docker_base_image_combo.setCurrentIndex(docker_index)
        self._is_syncing_default_docker_image = False

    def handle_java_preferences_changed(self, *_args):
        if self._is_loading_config:
            return

        preferred_java_distribution = self.preferred_java_distribution_combo.currentData()
        preferred_java_version = self.preferred_java_version_combo.currentData()
        if preferred_java_distribution is None or preferred_java_version is None:
            return

        selected_image = None
        if self._manual_docker_image_override:
            current_image = self.default_docker_base_image_combo.currentText()
            if current_image in ConfigManager.get_docker_base_image_versions(
                preferred_java_distribution,
                preferred_java_version,
            ):
                selected_image = current_image

        self.refresh_docker_base_image_options(
            preferred_java_distribution,
            preferred_java_version,
            selected_image,
        )
        self._manual_docker_image_override = (
            self.default_docker_base_image_combo.currentText()
            != ConfigManager.get_default_docker_base_image_version(
                preferred_java_version,
                preferred_java_distribution,
            )
        )

    def handle_default_docker_image_changed(self, *_args):
        if self._is_loading_config or self._is_syncing_default_docker_image:
            return

        preferred_java_distribution = self.preferred_java_distribution_combo.currentData()
        preferred_java_version = self.preferred_java_version_combo.currentData()
        expected_default_image = ConfigManager.get_default_docker_base_image_version(
            preferred_java_version,
            preferred_java_distribution,
        )
        self._manual_docker_image_override = (
            self.default_docker_base_image_combo.currentText() != expected_default_image
        )
