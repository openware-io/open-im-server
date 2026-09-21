import os
import locale
import signal
import subprocess
import time
from rich.console import Console

console = Console()


class CommandExecutor:
    def __init__(self):
        self.process = None
        self.is_cancelled = False

    def run_command(self, command, cwd=None, env=None, output_callback=None):
        """
        执行命令，支持取消操作
        """
        try:
            if self.is_cancelled:  # 检查是否在开始前已被取消
                return

            console.print(f"[bold cyan]正在执行命令: {command}[/bold cyan]")
            if output_callback:
                output_callback(f"[INFO] 执行命令: {command}")
                if cwd:
                    output_callback(f"[INFO] 工作目录: {cwd}")

            process_encoding = locale.getpreferredencoding(False) or "utf-8"
            self.process = subprocess.Popen(
                command,
                cwd=cwd,
                env=env,
                shell=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                encoding=process_encoding,
                errors="replace",
                bufsize=1,
                # Windows上不再使用CREATE_NEW_PROCESS_GROUP
                preexec_fn=os.setsid if os.name != "nt" else None,
            )

            if self.process.stdout is not None:
                while True:
                    if self.is_cancelled:  # 如果被取消，立即终止进程
                        self._terminate_process()
                        raise subprocess.CalledProcessError(-1, command)

                    output_line = self.process.stdout.readline()
                    if output_line:
                        output_line = output_line.rstrip()
                        if output_line:
                            console.print(output_line)
                            if output_callback:
                                output_callback(output_line)
                        continue

                    if self.process.poll() is not None:
                        break

                    time.sleep(0.1)  # 减少CPU使用率

            while self.process.poll() is None:  # 检查进程是否还在运行
                if self.is_cancelled:  # 如果被取消，立即终止进程
                    self._terminate_process()
                    raise subprocess.CalledProcessError(-1, command)
                time.sleep(0.1)  # 减少CPU使用率

            return_code = self.process.returncode
            if return_code != 0:
                raise subprocess.CalledProcessError(return_code, command)

            console.print(f"[bold green]命令执行成功: {command}[/bold green]")
            if output_callback:
                output_callback(f"[SUCCESS] 命令执行成功: {command}")

        except subprocess.CalledProcessError as e:
            if not self.is_cancelled:  # 只在非取消状态下打印错误
                console.print(f"[bold red]命令执行失败: {command}[/bold red]\n{e}")
                if output_callback:
                    output_callback(f"[ERROR] 命令执行失败: {command}")
                    output_callback(str(e))
            raise e

    def _terminate_process(self):
        """终止进程的内部方法"""
        if self.process:
            try:
                if os.name == "nt":
                    # Windows上使用taskkill命令强制终止进程树
                    subprocess.run(
                        f"taskkill /F /T /PID {self.process.pid}",
                        shell=True,
                        check=False,
                    )
                else:
                    # Unix系统上发送SIGKILL信号到进程组
                    os.killpg(os.getpgid(self.process.pid), signal.SIGKILL)

                # 等待进程结束
                for _ in range(10):  # 最多等待1秒
                    if self.process.poll() is not None:
                        break
                    time.sleep(0.1)

            except:
                pass  # 忽略终止过程中的错误

    def cancel(self):
        """
        取消正在执行的命令
        """
        self.is_cancelled = True
        self._terminate_process()


def run_shell_command(command, cwd=None, env=None, output_callback=None):
    """
    为了保持兼容性的包装函数
    """
    executor = CommandExecutor()
    return executor.run_command(command, cwd, env, output_callback)


def print_info(message):
    console.print(f"[bold yellow]{message}[/bold yellow]")


def print_error(message):
    console.print(f"[bold red]{message}[/bold red]")


def print_success(message):
    console.print(f"[bold green]{message}[/bold green]")
