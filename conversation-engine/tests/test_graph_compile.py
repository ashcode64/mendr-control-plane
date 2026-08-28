from app.graph import build_graph
from app.llm import Proposer
from app.mcp_client import McpClient


def test_build_graph_compiles():
    assert build_graph(Proposer(), McpClient()) is not None
