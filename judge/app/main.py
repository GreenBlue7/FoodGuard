from fastapi import FastAPI

app = FastAPI(title="FoodGuard Judge", version="0.1.0")

@app.get("/health")
def health() -> dict:
    return {"status": "ok"}

@app.post("/judge")
def judge(body: dict) -> dict:
    return {
        "stub": True,
        "message": "기본 서비스 뼈대"
    }