"""pytest 全局配置：把 agent 根目录加入 sys.path，使 core/api/config 等模块可直接导入"""
import os
import sys

# CI 等无 .env 环境下，OpenAI 客户端在模块导入时就会因缺 key 报错；
# 测试全部 mock LLM 调用，给一个占位 key 即可（必须在导入项目模块前设置）
os.environ.setdefault("KA_DEEPSEEK_API_KEY", "test-key")

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
