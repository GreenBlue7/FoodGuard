# Base image: python 3.13, slim --> means slim version of Debian
FROM python:3.13-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    HF_HOME=/cache/huggingface

WORKDIR /app

COPY requirements.txt ./

RUN pip install --no-cache-dir torch --index-url https://download.pytorch.org/whl/cpu \
    && pip install --no-cache-dir -r requirements.txt

# Bake the embedding model into the image so the first inspection does not wait for a 2GB+ download.
# Only the files sentence-transformers loads; the repo also holds ONNX and safetensors copies
RUN python -c "from huggingface_hub import snapshot_download; snapshot_download('BAAI/bge-m3', allow_patterns=['*.json', 'pytorch_model.bin', 'sentencepiece.bpe.model'])"

# Fail the build here, not at the first inspection, if a required file was not downloaded
RUN HF_HUB_OFFLINE=1 python -c "from sentence_transformers import SentenceTransformer; SentenceTransformer('BAAI/bge-m3')"

# Bake the Korean OCR model for image inspection (box positions) so the first image does not wait for a download.
# Only text_locate.py is copied here, so other code changes do not re-download it
COPY app/text_locate.py ./app/text_locate.py
RUN python -c "import sys; sys.path.insert(0, 'app'); import text_locate; text_locate.warm_up()"

# Never contact Hugging Face at runtime: no rate-limit warnings and no background downloads
ENV HF_HUB_OFFLINE=1

COPY . .

EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
