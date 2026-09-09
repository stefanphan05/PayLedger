FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Bake the embedding model into the image. Without this the container downloads
# ~90 MB on its first request, which turns a cold start into a timeout.
# Must match EMBEDDING_MODEL in app/shared/config.py.
RUN python -c "from sentence_transformers import SentenceTransformer; \
    SentenceTransformer('all-MiniLM-L6-v2')"

COPY app/ ./app/

EXPOSE 8000

# --host 0.0.0.0 is required. Uvicorn's default 127.0.0.1 binds to the
# container's own loopback, which nothing outside the container can reach —
# the published port would connect and be refused.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]