"""临时脚本：给演示账号的三个工作流写入图数据（仅用于本地冒烟，不属于前端产物）。"""
import json
import sys
import urllib.request

TOKEN = sys.argv[1]
A, B, C = sys.argv[2], sys.argv[3], sys.argv[4]
BASE = 'http://127.0.0.1:8080/api'


def put(path, payload):
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(payload).encode('utf-8'),
        method='PUT',
        headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + TOKEN},
    )
    with urllib.request.urlopen(req) as r:
        return r.status, r.read().decode('utf-8')


def node(key, typ, name, config, y):
    return {'nodeKey': key, 'type': typ, 'name': name, 'config': config, 'positionX': 0, 'positionY': y}


branch = {
    'nodes': [
        node('input_1', 'input', '输入', {
            'fields': [{'key': 'city', 'label': '城市', 'type': 'text', 'default': '北京'}]
        }, 0),
        node('condition_1', 'condition', '是否北京', {
            'rules': [{'left': '{{input_1.city}}', 'operator': 'equals', 'right': '北京', 'handle': 'beijing'}],
            'default_handle': 'other',
        }, 1),
        node('llm_1', 'llm', '北京专属建议', {
            'model': 'deepseek-flash',
            'prompt': '用户所在城市是北京：{{input_1.city}}。请给一句北京出行提醒，不超过 30 字。',
            'temperature': 0.7,
            'output_key': 'text',
        }, 2),
        node('llm_2', 'llm', '其他城市建议', {
            'model': 'deepseek-flash',
            'prompt': '用户所在城市不是北京，而是：{{input_1.city}}。请给一句出行提醒，不超过 30 字。',
            'temperature': 0.7,
            'output_key': 'text',
        }, 3),
    ],
    'edges': [
        {'sourceNodeKey': 'input_1', 'targetNodeKey': 'condition_1', 'sourceHandle': None},
        {'sourceNodeKey': 'condition_1', 'targetNodeKey': 'llm_1', 'sourceHandle': 'beijing'},
        {'sourceNodeKey': 'condition_1', 'targetNodeKey': 'llm_2', 'sourceHandle': 'other'},
    ],
}

weather = {
    'nodes': [
        node('input_1', 'input', '输入', {
            'fields': [{'key': 'city', 'label': '城市', 'type': 'text', 'default': '南昌'}]
        }, 0),
        node('http_1', 'http', '查天气', {
            'tool_id': 1,
            'params': {'city': '{{input_1.city}}'},
            'output_key': 'data',
        }, 1),
        node('llm_1', 'llm', 'AI 建议', {
            'model': 'deepseek-flash',
            'prompt': '城市 {{input_1.city}} 的天气数据：{{http_1.data}}\n请给出 3 条简洁的穿衣建议，分点列出。',
            'temperature': 0.7,
            'output_key': 'text',
        }, 2),
    ],
    'edges': [
        {'sourceNodeKey': 'input_1', 'targetNodeKey': 'http_1', 'sourceHandle': None},
        {'sourceNodeKey': 'http_1', 'targetNodeKey': 'llm_1', 'sourceHandle': None},
    ],
}

broken = {
    'nodes': [
        node('input_1', 'input', '输入', {
            'fields': [{'key': 'city', 'label': '城市', 'type': 'text', 'default': '南昌'}]
        }, 0),
        node('http_1', 'http', '查天气（工具填错）', {
            'tool_id': 999,
            'params': {'city': '{{input_1.city}}'},
            'output_key': 'data',
        }, 1),
        node('llm_1', 'llm', 'AI 建议', {
            'model': 'deepseek-flash',
            'prompt': '城市 {{input_1.city}} 天气：{{http_1.data}}，给一句建议。',
            'temperature': 0.7,
            'output_key': 'text',
        }, 2),
    ],
    'edges': [
        {'sourceNodeKey': 'input_1', 'targetNodeKey': 'http_1', 'sourceHandle': None},
        {'sourceNodeKey': 'http_1', 'targetNodeKey': 'llm_1', 'sourceHandle': None},
    ],
}

for wid, graph, label in ((A, branch, 'branch'), (B, weather, 'weather'), (C, broken, 'broken')):
    status, body = put(f'/workflows/{wid}/graph', graph)
    print(label, wid, status, body)
