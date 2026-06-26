from pathlib import Path

from langchain_core.documents import Document
from langchain_text_splitters import RecursiveCharacterTextSplitter
from langchain_ollama import OllamaEmbeddings
from langchain_chroma import Chroma
import shutil
import sys

# Đảm bảo in được tiếng Việt trên console Windows (mặc định cp1252 sẽ crash).
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

ROOT_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT_DIR))

from app.config import CHROMA_DIR, EMBED_MODEL_NAME, RAG_DOCS_DIR


def load_markdown_documents():
    documents = []

    for file_path in RAG_DOCS_DIR.glob("**/*.md"):
        content = file_path.read_text(encoding="utf-8")

        relative_path = file_path.relative_to(RAG_DOCS_DIR)

        documents.append(
            Document(
                page_content=content,
                metadata={
                    "source": str(relative_path).replace("\\", "/"),
                    "file_name": file_path.name,
                    "doc_type": file_path.parent.name
                }
            )
        )

    return documents


def main():
    documents = load_markdown_documents()

    if not documents:
        print("Không tìm thấy tài liệu .md trong data/rag_docs")
        return

    # Cắt theo ranh giới section "## skill_code:" để mỗi chunk = đúng 1 skill_code,
    # tự chứa định danh + đầy đủ các mục (tránh chunk "mồ côi" mất skill_code).
    splitter = RecursiveCharacterTextSplitter(
        chunk_size=2000,
        chunk_overlap=100,
        separators=["\n---\n", "\n## ", "\n\n", "\n", " ", ""]
    )

    chunks = splitter.split_documents(documents)

    print(f"Số tài liệu gốc: {len(documents)}")
    print(f"Số chunks sau khi cắt: {len(chunks)}")

    if CHROMA_DIR.exists():
        shutil.rmtree(CHROMA_DIR)
        print("Đã xóa ChromaDB cũ.")
        
    embeddings = OllamaEmbeddings(model=EMBED_MODEL_NAME)

    Chroma.from_documents(
        documents=chunks,
        embedding=embeddings,
        persist_directory=str(CHROMA_DIR)
    )

    print("Đã tạo xong ChromaDB tại data/chroma_db")


if __name__ == "__main__":
    main()
