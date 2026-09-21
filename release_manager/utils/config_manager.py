import json
import os

from utils.common_dict import CommonDict


class ConfigManager:
    @staticmethod
    def get_default_java_version():
        return "25"

    @staticmethod
    def get_default_cloud_vendor():
        return CommonDict.DEFAULT_CLOUD_VENDOR

    @staticmethod
    def get_default_java_distribution(cloud_vendor=None):
        resolved_cloud_vendor = str(
            cloud_vendor or ConfigManager.get_default_cloud_vendor()
        )
        if resolved_cloud_vendor not in CommonDict.CLOUD_VENDORS:
            resolved_cloud_vendor = ConfigManager.get_default_cloud_vendor()
        return CommonDict.DEFAULT_JAVA_DISTRIBUTION_BY_CLOUD_VENDOR.get(
            resolved_cloud_vendor,
            CommonDict.DEFAULT_JAVA_DISTRIBUTION,
        )

    @staticmethod
    def get_docker_base_image_versions(java_distribution=None, java_version=None):
        resolved_java_distribution = (
            str(java_distribution or ConfigManager.get_default_java_distribution())
        )
        if resolved_java_distribution not in CommonDict.JAVA_DISTRIBUTIONS:
            resolved_java_distribution = ConfigManager.get_default_java_distribution()

        resolved_java_version = str(java_version or ConfigManager.get_default_java_version())
        if resolved_java_version not in CommonDict.JAVA_LTS_VERSIONS:
            resolved_java_version = ConfigManager.get_default_java_version()

        distribution_options = CommonDict.DOCKER_BASE_IMAGE_OPTIONS_BY_JAVA.get(
            resolved_java_distribution,
            CommonDict.DOCKER_BASE_IMAGE_OPTIONS_BY_JAVA[
                ConfigManager.get_default_java_distribution()
            ],
        )
        return list(
            distribution_options.get(
                resolved_java_version,
                distribution_options[ConfigManager.get_default_java_version()],
            )
        )

    @staticmethod
    def get_default_docker_base_image_version(java_version=None, java_distribution=None):
        resolved_java_distribution = (
            str(java_distribution or ConfigManager.get_default_java_distribution())
        )
        if resolved_java_distribution not in CommonDict.JAVA_DISTRIBUTIONS:
            resolved_java_distribution = ConfigManager.get_default_java_distribution()

        resolved_java_version = str(java_version or ConfigManager.get_default_java_version())
        if resolved_java_version not in CommonDict.JAVA_LTS_VERSIONS:
            resolved_java_version = ConfigManager.get_default_java_version()

        distribution_defaults = (
            CommonDict.DEFAULT_DOCKER_BASE_IMAGE_BY_JAVA_DISTRIBUTION.get(
                resolved_java_distribution,
                CommonDict.DEFAULT_DOCKER_BASE_IMAGE_BY_JAVA_DISTRIBUTION[
                    ConfigManager.get_default_java_distribution()
                ],
            )
        )
        return distribution_defaults.get(
            resolved_java_version,
            distribution_defaults[ConfigManager.get_default_java_version()],
        )

    @staticmethod
    def get_release_manager_root():
        current_file_dir = os.path.dirname(os.path.abspath(__file__))
        return os.path.dirname(current_file_dir)

    @staticmethod
    def get_config_file_path():
        """
        获取运行时配置文件路径。
        """
        runtime_dir = os.path.join(
            ConfigManager.get_release_manager_root(), "runtime"
        )
        os.makedirs(runtime_dir, exist_ok=True)
        return os.path.join(runtime_dir, "config.json")

    @staticmethod
    def get_resource_path(*relative_parts):
        return os.path.join(
            ConfigManager.get_release_manager_root(), "resources", *relative_parts
        )

    @staticmethod
    def get_project_root():
        """
        获取仓库根目录的绝对路径。
        """
        return os.path.dirname(ConfigManager.get_release_manager_root())

    @staticmethod
    def init_config():
        """
        初始化配置，并在已有配置上补齐缺失字段。
        """
        default_config = {
            CommonDict.DOCKER_REGISTRY: "",
            CommonDict.DOCKER_NAMESPACE: "gv_im_server",
            CommonDict.DOCKER_USERNAME: "",
            CommonDict.DOCKER_PASSWORD: "",
            CommonDict.PREFERRED_CLOUD_VENDOR: ConfigManager.get_default_cloud_vendor(),
            CommonDict.PREFERRED_JAVA_DISTRIBUTION: ConfigManager.get_default_java_distribution(),
            CommonDict.PREFERRED_JAVA_VERSION: ConfigManager.get_default_java_version(),
            CommonDict.PKG_ENV: "dev",
            CommonDict.PROJECT_ROOT_DIR: ConfigManager.get_project_root(),
            CommonDict.DOCKER_BASE_IMAGE_VERSION: ConfigManager.get_default_docker_base_image_version(),
            "otel_monitoring": False,
            "arthas": False,
        }

        config_file = ConfigManager.get_config_file_path()
        if os.path.exists(config_file):
            config = ConfigManager.load_config()
        else:
            config = {}

        merged_config = {**default_config, **config}
        preferred_cloud_vendor = str(
            merged_config.get(
                CommonDict.PREFERRED_CLOUD_VENDOR,
                ConfigManager.get_default_cloud_vendor(),
            )
        )
        if preferred_cloud_vendor not in CommonDict.CLOUD_VENDORS:
            preferred_cloud_vendor = ConfigManager.get_default_cloud_vendor()
        merged_config[CommonDict.PREFERRED_CLOUD_VENDOR] = preferred_cloud_vendor

        preferred_java_distribution = str(
            merged_config.get(
                CommonDict.PREFERRED_JAVA_DISTRIBUTION,
                ConfigManager.get_default_java_distribution(preferred_cloud_vendor),
            )
        )
        if preferred_java_distribution not in CommonDict.JAVA_DISTRIBUTIONS:
            preferred_java_distribution = ConfigManager.get_default_java_distribution(
                preferred_cloud_vendor
            )
        merged_config[CommonDict.PREFERRED_JAVA_DISTRIBUTION] = preferred_java_distribution

        preferred_java_version = str(
            merged_config.get(
                CommonDict.PREFERRED_JAVA_VERSION,
                ConfigManager.get_default_java_version(),
            )
        )
        if preferred_java_version not in CommonDict.JAVA_LTS_VERSIONS:
            preferred_java_version = ConfigManager.get_default_java_version()
        merged_config[CommonDict.PREFERRED_JAVA_VERSION] = preferred_java_version

        docker_base_image_version = merged_config.get(
            CommonDict.DOCKER_BASE_IMAGE_VERSION
        )
        if docker_base_image_version not in CommonDict.DOCKER_BASE_IMAGE_VERSIONS:
            merged_config[
                CommonDict.DOCKER_BASE_IMAGE_VERSION
            ] = ConfigManager.get_default_docker_base_image_version(
                preferred_java_version,
                preferred_java_distribution,
            )

        project_root_dir = merged_config.get(CommonDict.PROJECT_ROOT_DIR)
        if not project_root_dir or not os.path.exists(project_root_dir):
            merged_config[CommonDict.PROJECT_ROOT_DIR] = ConfigManager.get_project_root()

        ConfigManager.save_config(merged_config)

    @staticmethod
    def load_config():
        """
        从配置文件中加载配置数据，如果文件不存在则返回空字典。
        """
        config_file = ConfigManager.get_config_file_path()
        if os.path.exists(config_file):
            with open(config_file, "r", encoding="utf-8") as f:
                return json.load(f)
        return {}

    @staticmethod
    def save_config(config_data):
        """
        将配置数据保存到配置文件中。
        """
        config_file = ConfigManager.get_config_file_path()
        with open(config_file, "w", encoding="utf-8") as f:
            json.dump(config_data, f, indent=4, ensure_ascii=False)

    @staticmethod
    def save_pkg_env(environment):
        """
        将打包环境保存到配置文件中。
        """
        config = ConfigManager.load_config()
        config[CommonDict.PKG_ENV] = environment
        ConfigManager.save_config(config)

    @staticmethod
    def get_config_value(key, default_value=None):
        """
        添加配置值验证
        """
        config = ConfigManager.load_config()
        value = config.get(key, default_value)

        # 对特定配置项进行验证
        if key == CommonDict.PROJECT_ROOT_DIR and value:
            # 如果是相对路径，则基于项目根目录进行解析
            if not os.path.isabs(value):
                value = os.path.join(ConfigManager.get_project_root(), value)
            if not os.path.exists(value):
                return default_value

        return value

    @staticmethod
    def set_config_value(key, value):
        """
        设置配置项的值。
        """
        config = ConfigManager.load_config()
        config[key] = value
        ConfigManager.save_config(config)
