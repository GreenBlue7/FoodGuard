package com.my.food_ad_check.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.my.food_ad_check.entity.InspectionRecord;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;

/** HTML generated only from the owned inspection's stored snapshot. No AI or current-product lookup. */
public final class InspectionReportRenderer {
    private static final ObjectMapper JSON=new ObjectMapper();
    private InspectionReportRenderer() { }
    private record Finding(int index,String verdict,String text,JsonNode data,boolean legacy) { }

    public static String render(InspectionRecord record) {
        try {
            JsonNode root=JSON.readTree(record.getResultJson());
            if(root==null || !root.isObject()) throw new IllegalArgumentException("Missing result");
            List<Finding> findings=findings(root);
            int violations=(int)findings.stream().filter(f->f.verdict().equals("VIOLATION")).count();
            int reviews=(int)findings.stream().filter(f->f.verdict().equals("REVIEW")||f.verdict().equals("HOLD")).count();
            int ok=findings.size()-violations-reviews;
            var metadata=InspectionHistoryMetadata.read(record.getStatus(),record.getResultJson());
            String documentNo=record.getId()==null?"기록 없음":String.format(Locale.ROOT,"FG-%06d",record.getId());
            StringBuilder body=new StringBuilder();
            if(root.path("demo").asBoolean(false))body.append("<aside class='demo'>시연 데이터 · 실제 AI 검수 결과가 아닙니다.</aside>");
            body.append("<header><div class='document-line'><span class='brand'>FoodGuard</span><span class='document-no'>문서번호 ")
                    .append(e(documentNo)).append("</span></div><h1>식품 광고 문구 검토 보고서</h1><p class='subtitle'>제출 광고 문구에 대한 사전점검 기록</p></header>");
            String date=record.getCreatedAt()==null?null:record.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm"));
            String model=text(root.path("layer2").path("model"),text(root.path("judgeResponse").path("model"),null));
            body.append("<table class='identity' aria-label='검토 기본 정보'><colgroup><col class='identity-key'><col><col class='identity-key'><col></colgroup><tbody>")
                    .append(identityRow("제품명",record.getPrdlstNm(),"검토일시",date))
                    .append(identityRow("제조사",record.getBsshNm(),"자료 기준일",metadata.dataAsOf()))
                    .append(identityRow("신고번호",record.getPrdlstReportNo(),"제품 종류",record.getProductType()))
                    .append(identityRow("검토대상","제출 광고 문구","판정 모델",model))
                    .append("</tbody></table><p class='caption'>제품·자료·판정 정보는 검사 당시 저장된 기록을 기준으로 작성했습니다.</p>");
            body.append("<section class='summary'><h2>검토 결과 요약</h2><p class='summary-line'>총 <strong>").append(findings.size())
                    .append("건</strong>의 판정 항목 중 <strong class='violation'>위반 판정 ").append(violations)
                    .append("건</strong>, <strong class='review'>검토 필요 ").append(reviews)
                    .append("건</strong>, <span>문제없음 ").append(ok).append("건</span>이 기록되었습니다.</p>");
            if(violations+reviews>0)body.append("<p class='action-line'>우선 확인 대상 <strong>").append(violations+reviews)
                    .append("건</strong>은 아래 표의 앞부분에 배치했습니다. 대상 문구와 검토 의견·근거를 함께 확인하세요.</p>");
            body.append("<p class='caption'>문장 수가 아닌 판정 항목 수입니다. 여러 문장이 묶인 지적은 1건으로 세며, 보류 판정은 검토 필요에 포함합니다.</p></section>");
            if(metadata.dataAsOf()==null)body.append("<p class='warning'>자료 기준일 기록이 없습니다. 현재 기준의 적합성을 뜻하지 않습니다.</p>");
            if(findings.isEmpty())body.append("<p class='warning'>저장된 판정 항목이 0건입니다. 광고 전체가 적합하다는 뜻으로 해석하지 마세요.</p>");
            body.append("<section><h2>세부 검토 내용</h2><p class='table-note'>위반 → 검토 필요 → 문제없음 순으로 배열했습니다.</p>")
                    .append("<table class='findings' aria-label='항목별 광고 문구 검토'><colgroup><col class='number-col'><col class='quote-col'><col class='verdict-col'><col></colgroup>")
                    .append("<thead><tr><th scope='col'>번호</th><th scope='col'>대상 문구</th><th scope='col'>판정</th><th scope='col'>검토 의견 및 근거</th></tr></thead><tbody>");
            int displayNumber=1;
            for(Finding finding:findings.stream().sorted(Comparator.comparingInt(f->rank(f.verdict()))).toList())body.append(detail(finding,displayNumber++));
            if(findings.isEmpty())body.append("<tr><td colspan='4'>기록된 판정 항목이 없습니다.</td></tr>");
            body.append("</tbody></table></section>");
            body.append(comparison(record,root));
            body.append("<section><h2>검토 대상 광고 원문</h2><div class='original'>").append(e(record.getAdText())).append("</div></section>");
            if(record.getPrimaryFnclty()!=null && !record.getPrimaryFnclty().isBlank())body.append("<section class='appendix'><h2>검사 당시 기능성 원문</h2><div class='original'>").append(e(record.getPrimaryFnclty())).append("</div></section>");
            body.append("<aside class='notice'>광고 수정 협의와 내부 검토를 위한 사전점검 기록입니다. 식약처 승인서·공식 광고 심의서·법적 적합 증명서가 아닙니다.</aside>");
            body.append("<footer><p>").append(e(documentNo)).append(" · FoodGuard</p><p>자료·모델의 한계가 있으며 최종 광고 게시 여부는 별도 검토가 필요합니다. 리포트 열람·저장은 재검수나 새 추천 생성을 실행하지 않습니다.</p></footer>");
            String nonce=UUID.randomUUID().toString();
            return new ClassPathResource("report/report.html").getContentAsString(StandardCharsets.UTF_8)
                    .replace("__NONCE__",nonce).replace("__REPORT_ID__",String.valueOf(record.getId())).replace("__CONTENT__",body);
        } catch(ResponseStatusException error) { throw error; }
        catch(Exception error) { throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"저장된 판정 정보를 읽을 수 없어 리포트를 만들지 못했습니다."); }
    }

    private static List<Finding> findings(JsonNode root) {
        JsonNode items=root.path("layer2").path("findings"); boolean legacy=false;
        if(items.isNull()||items.isMissingNode()){items=root.path("judgeResponse").path("results");legacy=true;}
        if(!items.isArray())throw new IllegalArgumentException("Missing verdicts");
        List<Finding> result=new ArrayList<>();int index=0;
        for(JsonNode item:items){
            String verdict=text(item.path(legacy?"status":"verdict"),"");
            if(!Set.of("VIOLATION","REVIEW","HOLD","OK").contains(verdict))throw new IllegalArgumentException("Unknown verdict");
            String quote=legacy?text(item.path("text"),null):null;
            if(quote==null){List<String> parts=new ArrayList<>();boolean invalid=false;
                for(JsonNode id:item.path("sentenceIndexes")){
                    if(id.isIntegralNumber()&&id.canConvertToInt()&&id.intValue()>=0){
                        JsonNode sentence=root.path("layer2").path("sentences").path(id.intValue());
                        if(sentence.path("text").isTextual())parts.add(sentence.path("text").textValue());
                        else invalid=true;
                    } else invalid=true;
                }
                quote=parts.isEmpty()||invalid?"지적 문장의 원문 기록 없음":String.join("\n",parts);
            }
            result.add(new Finding(index++,verdict,quote,item,legacy));
        }
        return result;
    }

    private static String detail(Finding f,int number) {
        StringBuilder h=new StringBuilder("<tr class='finding ").append(tone(f.verdict())).append("' id='finding-").append(f.index()).append("'>")
                .append("<td class='row-number' data-label='번호'>").append(String.format(Locale.ROOT,"%02d",number)).append("</td>")
                .append("<td class='quote' data-label='대상 문구'>").append(e(f.text())).append("</td>")
                .append("<td class='verdict' data-label='판정'><strong class='").append(tone(f.verdict())).append("'>").append(label(f.verdict())).append("</strong></td>")
                .append("<td class='opinion' data-label='검토 의견 및 근거'><p class='opinion-text'>").append(e(text(f.data().path("reason"),"검토 의견 기록 없음"))).append("</p>");
        h.append("<div class='evidence-block'><h3>근거</h3>");
        boolean hasLaw=false;
        for(JsonNode law:f.data().path("lawRefs")){
            hasLaw=true;h.append("<p>").append(e(text(law.path("title"),""))).append(" ").append(e(text(law.path("article"),"")))
                    .append("</p>");
            if(law.path("text").isTextual())h.append("<p class='preserve'>").append(e(law.path("text").textValue())).append("</p>");
        }
        if(!hasLaw)h.append("<p>").append(e(text(f.data().path("violationType"),"법령·유형 근거 기록 없음"))).append("</p>");
        JsonNode evidence=f.data().path("evidence");
        if(!evidence.isArray()||evidence.isEmpty())evidence=f.data().path(f.legacy()?"evidence":"cases");
        h.append("</div><div class='evidence-block'><h3>참고 사례·자료</h3>");
        if(!evidence.isArray()||evidence.isEmpty())h.append("<p>기록 없음</p>");
        else{h.append("<ul>");for(JsonNode item:evidence){
            String title=text(item.path("title"),text(item.path("excerpt"),text(item.path("id"),"참고 자료")));
            String url=text(item.path("url"),text(item.path("source"),null));
            h.append("<li>").append(link(title,url)).append("</li>");
        }h.append("</ul>");}
        h.append("</div>");
        if(f.data().path("revision").isTextual()&&!f.data().path("revision").textValue().isBlank())
            h.append("<div class='revision'><h3>저장된 수정 예시</h3><p class='preserve'>").append(e(f.data().path("revision").textValue()))
                    .append("</p><p class='caption'>수정 예시는 별도 재검수 전까지 적합성이 확인된 문구가 아닙니다.</p></div>");
        return h.append("</td></tr>").toString();
    }

    private static String identityRow(String firstKey,String firstValue,String secondKey,String secondValue){
        return "<tr><th scope='row'>"+e(firstKey)+"</th><td>"+e(firstValue==null||firstValue.isBlank()?"기록 없음":firstValue)
                +"</td><th scope='row'>"+e(secondKey)+"</th><td>"+e(secondValue==null||secondValue.isBlank()?"기록 없음":secondValue)+"</td></tr>";
    }

    private static String comparison(InspectionRecord record,JsonNode root) {
        JsonNode c=root.path("comparison");
        if(record.getPreviousInspectionId()==null&&!c.isObject())return "";
        StringBuilder h=new StringBuilder("<section><h2>이전 검사와 비교</h2><p>이전 검사 #")
                .append(e(record.getPreviousInspectionId()==null?text(c.path("previousJobId"),"기록 없음"):record.getPreviousInspectionId().toString())).append("</p>");
        boolean same=c.path("sameRecordedCriteria").asBoolean(false);
        if(!same)h.append("<p class='warning'>제품 정보·모델·자료 기준이 달라졌거나 이전 기준을 확인할 수 없습니다. 문구 수정만으로 문제가 해결됐다고 해석하지 마세요.</p>");
        if(!c.path("sentenceComparisonAvailable").asBoolean(false))return h.append("<p>문장별 비교 기록 없음</p></section>").toString();
        if(same){JsonNode s=c.path("sentenceSummary");h.append("<p class='comparison-summary'>이번 검사에서 해소 ")
                .append(number(s.path("resolvedThisCheck"))).append(" · 남은 문제 ").append(number(s.path("remainingIssues")))
                .append(" · 새 문제 ").append(number(s.path("newIssues"))).append("</p>");}
        h.append("<p class='caption'>삭제된 문장은 해결로 세지 않습니다. 추정 연결과 판정 변경은 별도 확인이 필요합니다.</p><div class='table-wrap'><table><thead><tr><th>구분</th><th>이전 문장</th><th>이번 문장</th></tr></thead><tbody>");
        for(JsonNode row:c.path("sentenceChanges")){
            String code=text(row.path("issueChange"),"");
            String description=switch(code){
                case "RESOLVED_THIS_CHECK"->same?"이번 검사에서 해소":"기준 변경 · 해소 확정 불가";
                case "REMAINING_ISSUE"->"문제 남음";case "NEW_ISSUE"->"새 문제";
                case "REMOVED_NOT_ASSESSED"->"삭제 · 해결 집계 제외";
                case "MAPPING_REQUIRES_CONFIRMATION"->"문장 연결 확인 필요";
                case "UNCHANGED_VERDICT_CHANGED"->"동일 문장의 판정 변경";
                case "CRITERIA_CHANGED_OR_UNKNOWN"->"기준 변경·확인 불가";
                case "NO_ISSUE"->"이번 검사에서 문제없음";default->"비교 확인 필요";};
            h.append("<tr><td>").append(e(description)).append("</td><td>").append(e(text(row.path("before").path("text"),"—")))
                    .append("</td><td>").append(e(text(row.path("after").path("text"),"—"))).append("</td></tr>");
        }
        return h.append("</tbody></table></div></section>").toString();
    }

    private static int rank(String status){return status.equals("VIOLATION")?0:status.equals("OK")?2:1;}
    private static String tone(String status){return status.equals("VIOLATION")?"violation":status.equals("OK")?"ok":"review";}
    private static String label(String status){return status.equals("VIOLATION")?"위반":status.equals("OK")?"문제없음":status.equals("HOLD")?"보류 · 검토 필요":"검토 필요";}
    private static String e(String value){return HtmlUtils.htmlEscape(value==null?"":value);}
    private static String text(JsonNode node,String fallback){return node.isTextual()&&!node.textValue().isBlank()?node.textValue():fallback;}
    private static String number(JsonNode node){return node.isIntegralNumber()&&node.canConvertToInt()&&node.intValue()>=0?node.asText():"기록 없음";}
    private static String link(String title,String url){
        try{URI uri=URI.create(url==null?"":url);if(("https".equalsIgnoreCase(uri.getScheme())||"http".equalsIgnoreCase(uri.getScheme()))&&uri.getHost()!=null&&uri.getUserInfo()==null)
            return "<a href='"+e(url)+"' target='_blank' rel='noopener noreferrer'>"+e(title)+"</a>";}
        catch(IllegalArgumentException ignored){ }
        return e(title);
    }
}
