from langchain_ollama import OllamaEmbeddings
from langchain_chroma import Chroma
from pathlib import Path
import sys

ROOT_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT_DIR))

from app.config import CHROMA_DIR, EMBED_MODEL_NAME


def main():
    embeddings = OllamaEmbeddings(model=EMBED_MODEL_NAME)

    vectorstore = Chroma(
        persist_directory=str(CHROMA_DIR),
        embedding_function=embeddings
    )

    queries = [
        "Sinh viên không hiển thị lỗi khi email rỗng",
        "Ứng dụng bị loading vô hạn khi API timeout",
        "Bấm nút nhưng không chuyển sang màn hình tiếp theo",
        "Bài nộp sai định dạng tên file zip",
        "Dữ liệu thay đổi nhưng giao diện không cập nhật"
    ]

    for query in queries:
        print("=" * 80)
        print("QUERY:", query)

        docs = vectorstore.similarity_search(query, k=3)

        for index, doc in enumerate(docs, start=1):
            print(f"\n--- Result {index} ---")
            print("Source:", doc.metadata.get("source", "unknown"))
            print(doc.page_content[:500])


if __name__ == "__main__":
    main()
