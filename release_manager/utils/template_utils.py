# 导入必要的模块
import re


class DockerTemplateUtil:

    @staticmethod
    def replace_template(template_str, config):
        """
        根据配置替换模板中的占位符
        :param template_str: 模板字符串，里面包含需要替换的占位符
        :param config: 配置字典，键值对表示占位符及其替换内容
        :return: 替换后的字符串
        """
        # 使用正则表达式查找所有的占位符（形如 {{key}} ）
        pattern = re.compile(r"{{\s*(\w+)\s*}}")

        # 定义替换函数
        def replace_match(match):
            key = match.group(1)  # 取出占位符中的 key 名称
            return config.get(
                key, match.group(0)
            )  # 如果配置中有对应的 key，返回值，否则返回原样

        # 使用替换函数进行替换
        return pattern.sub(replace_match, template_str)

    @staticmethod
    def read_template(file_path):
        """
        从文件读取模板内容
        :param file_path: 文件路径
        :return: 模板的文本内容
        """
        with open(file_path, "r", encoding="utf-8") as file:
            return file.read()
