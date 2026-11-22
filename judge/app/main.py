from typing import Annotated

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from pydantic import ValidationError

from app.image_judge import ImageInputError, NoAdTextError, judge_images
from app.image_schemas import ImageJudgeRequest, ImageJudgeResponse
from app.llm import LlmError
from app.pipeline import run_judge
from app.retriever import RetrievalError
from app.schemas import JudgeRequest, JudgeResponse

app = FastAPI(title="FoodGuard Judge", version="0.1.0")

MAX_IMAGES = 10
MAX_IMAGE_BYTES = 50 * 1024 * 1024  # 한 장. 세로 3만 px PNG 캡처가 40MB 안팎이다
MAX_TOTAL_BYTES = 100 * 1024 * 1024  # 한 요청 합계


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


@app.post("/judge")
def judge(request: JudgeRequest) -> JudgeResponse:
    try:
        return run_judge(request)
    except (LlmError, RetrievalError) as e:
        raise HTTPException(status_code=503, detail=f"판정을 완료하지 못했습니다: {e}")


@app.post("/judge/image")
def judge_image(
    request_json: Annotated[str, Form(alias="request")],
    images: Annotated[list[UploadFile], File()],
) -> ImageJudgeResponse:
    # multipart: request = JSON 문자열(/judge 요청에서 adText 를 뺀 것), images = 이미지 파일들 (보낸 순서 = 위→아래)
    try:
        request = ImageJudgeRequest.model_validate_json(request_json)
    except ValidationError as e:
        raise HTTPException(status_code=400, detail=f"request 형식이 맞지 않습니다: {e.errors()[:3]}")
    if not 1 <= len(images) <= MAX_IMAGES:
        raise HTTPException(status_code=400, detail=f"이미지는 1~{MAX_IMAGES}장까지 올릴 수 있습니다.")

    files: list[bytes] = []
    for upload in images:
        data = upload.file.read(MAX_IMAGE_BYTES + 1)
        if len(data) > MAX_IMAGE_BYTES:
            raise HTTPException(status_code=400, detail=f"이미지 한 장은 {MAX_IMAGE_BYTES // 1024 // 1024}MB 이하만 올릴 수 있습니다.")
        files.append(data)
    if sum(len(data) for data in files) > MAX_TOTAL_BYTES:
        raise HTTPException(status_code=400, detail=f"이미지 합계는 {MAX_TOTAL_BYTES // 1024 // 1024}MB 이하만 올릴 수 있습니다.")

    try:
        return judge_images(request, files)
    except ImageInputError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except NoAdTextError as e:
        raise HTTPException(status_code=422, detail=str(e))
    except (LlmError, RetrievalError) as e:
        raise HTTPException(status_code=503, detail=f"판정을 완료하지 못했습니다: {e}")
