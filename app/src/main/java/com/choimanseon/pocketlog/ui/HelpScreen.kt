package com.choimanseon.pocketlog.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** One part of the app: [summary] is shown folded, [lines] when opened. */
private class Help(val title: String, val summary: String, val lines: List<String>)

private val overview = listOf(
    "카드 · 은행 · 페이 알림과 문자를 읽어 결제를 알아서 적어요.",
    "알림이 없는 결제는 주문내역 스크린샷이나 영수증 사진 한 장으로 AI가 품목까지 나눠 적어요.",
    "나머지는 아래 + 버튼으로 직접 적어요. 금액, 가맹점, 카테고리만 고르면 돼요.",
    "분석 · 예산 · 월간 리포트로 어디에 얼마나 썼는지 돌아봐요.",
    "나에게 들어오고 나가는 돈만 적어요. 내 계좌끼리 옮긴 돈, 카드값, 페이머니 충전은 적지 않아요.",
)

private val sections = listOf(
    Help("홈 탭", "이번 달 쓴 돈과 남은 예산", listOf(
        "맨 위에 이번 달 쓴 돈과, 지난달 이맘때보다 더 썼는지 덜 썼는지가 나와요.",
        "예산을 정해 두면 남은 예산과 '오늘은 얼마까지 괜찮은지'를 알려 줘요.",
        "'확인이 필요한 내역'은 자동 기록 중 확실하지 않은 건이에요. 눌러서 맞는지 봐 주세요.",
        "'저장하지 않은 스크린샷 분석'은 분석은 끝났지만 아직 저장하지 않은 건이에요.",
        "오른쪽 위 돋보기는 검색, 톱니바퀴는 설정이에요.",
    )),
    Help("내역 탭", "기록 보기 · 고치기 · 지우기", listOf(
        "화면을 좌우로 밀면 지난달 · 다음 달로 넘어가요. '달력으로 보기'를 누르면 날짜별로 봐요.",
        "한 줄을 왼쪽으로 밀면 삭제, 오른쪽으로 밀면 지금 시간으로 복제돼요. 삭제는 바로 되돌릴 수 있어요.",
        "한 줄을 길게 누르면 여러 개를 골라 한꺼번에 지우거나 카테고리를 바꿀 수 있어요.",
        "한 줄을 누르면 상세 화면이 열려요. '수정'으로 금액 · 카테고리 · 태그 등을 고쳐요.",
        "스크린샷으로 넣은 내역은 상세 화면의 품목 줄을 눌러 품목마다 카테고리 · 태그를 바꿀 수 있어요.",
    )),
    Help("검색", "초성이나 오타로도 찾아요", listOf(
        "가맹점, 품명, 메모로 찾아요.",
        "'ㅅㅌㅂ'처럼 초성만 쳐도, '스타박스'처럼 한 글자 틀려도 '비슷한 내역'으로 보여 줘요.",
        "기간, 카테고리, 결제수단, 금액 범위, '메모 있는 것만'으로 좁힐 수 있어요.",
    )),
    Help("분석 탭", "어디에, 언제 썼는지", listOf(
        "위에서 지출 · 수입 · 저축 · 합산과 기간 단위(주 · 월 · 연 · 직접 지정)를 골라요.",
        "분류: 카테고리별이나 태그별로 나눠 봐요. 한 내역에 태그가 여러 개면 태그마다 따로 더해서, 비율을 모두 더하면 100%를 넘을 수 있어요.",
        "기간: 기간마다 얼마 썼는지 막대로 봐요. 카테고리나 태그 하나만 골라 볼 수 있고(예: 식비 › #야식), 예산이 있으면 빨간 점선으로 보여요.",
        "패턴: 요일 × 시간대 표에서 진할수록 많이 쓴 때예요. 평일 · 주말 하루 평균과 자주 가는 곳도 여기 있어요.",
        "목록의 한 줄을 누르면 그 내역들을 모아 볼 수 있어요.",
        "'리포트'를 누르면 AI 월간 리포트를 봐요.",
    )),
    Help("자산 탭", "예산과 결제수단 잔액", listOf(
        "주 · 월 · 연 예산을 정하고, 카테고리별 예산도 따로 둘 수 있어요.",
        "'주 · 월 · 연 통일'을 켜면 하나를 바꿀 때 나머지가 맞춰져요(1년 = 12달 = 52주).",
        "기간마다 '예산 보이기'를 끄면 홈 · 자산 탭과 예산 알림에서 빠져요.",
        "예산의 50% · 80% · 100%를 쓰면 알림으로 알려 줘요.",
        "결제수단에 잔액을 적어 두면, 은행 · 페이머니는 알림에 찍힌 잔액으로 따라 바뀌어요.",
    )),
    Help("직접 입력 (+ 버튼)", "금액, 가맹점, 카테고리만 고르면 돼요", listOf(
        "키패드로 금액을 적어요. + − × ÷로 계산되고, ⌫를 길게 누르면 다 지워져요.",
        "한 곳에서 여러 가지를 샀다면 품목마다 금액 · 품명 · 카테고리 · 태그를 적고 왼쪽 아래 '추가'를 눌러요. 담은 품목은 위에 모이고, 다 적으면 '저장하기'로 한 건으로 저장해요. 통계는 품목별 카테고리 · 태그로 나눠요.",
        "담은 품목을 누르면 다시 고치고, ✕를 누르면 빠져요. 품목을 담는 동안은 지출만, 원화 일시불로만 적어요.",
        "'원화 ▾'를 누르면 외화로 적을 수 있어요. 그날 환율로 원화로 바꿔 저장해요.",
        "가맹점을 적으면 전에 그 가맹점에 쓴 카테고리 · 결제수단이 채워져요.",
        "카테고리와 태그(#)는 누르면 이번 기록에만 적용돼요.",
        "꾹(0.8초) 누르면 규칙이 돼요: 카테고리나 그 아래 태그를 꾹 누르면 금색으로 바뀌고, 그 가맹점은 다음부터 자동 기록에서도 그 분류로 들어가요. 금색 칩을 다시 꾹 누르면 규칙에서 빠져요.",
        "편의점처럼 그때그때 다른 곳은 꾹 누르지 않으면 돼요. 공통 태그(#데이트, #가족 …)는 그때그때 달라서 규칙에 넣지 않아요.",
        "만든 규칙은 설정 → 카드 문자 · 알림 자동 기록 → 카테고리 규칙에서 보고 지울 수 있어요.",
        "'즐겨찾기'를 누르면 자주 쓰는 내역을 골라 한 번에 채워요.",
        "품명은 무엇을 샀는지, '+ 메모'는 내 메모예요. 날짜 · 시간, 결제수단, 할부도 칩을 눌러 바꿔요.",
        "위의 '촬영' · '사진'으로 영수증이나 스크린샷을 바로 분석할 수 있어요.",
        "바깥을 누르거나, 뒤로 가기를 누르거나, 아래로 끌어내리면 닫혀요.",
    )),
    Help("스크린샷 · 영수증", "AI가 품목까지 나눠 적어요", listOf(
        "쇼핑 · 배달 · 페이 앱 주문내역을 캡처해 '사진'으로 고르거나, 다른 앱에서 공유하기로 Pocketlog에 보내요. 영수증은 '촬영'으로 찍어요.",
        "처음 한 번 AI 사용 동의가 필요해요. 이미지는 AI 서버를 거쳐 OpenAI로 가고, 서버에는 남지 않아요.",
        "긴 스크린샷은 여러 조각으로 나눠 8조각씩 보내요. 분석이 끝나면 알림으로 알려 줘요.",
        "검토 화면에서 금액 · 품목 이름 · 카테고리 · 메모를 눌러 고친 뒤 '저장하기'를 눌러요.",
        "품목의 카테고리 칩이나 태그를 꾹(0.8초) 누르면 그 품목 이름으로 규칙이 돼요. 직접 입력과 같아요.",
        "카드 문자로 이미 기록된 결제면 '이미 기록된 결제예요'가 떠요. '품목 넣기' · '품목 바꾸기'를 고르면 금액 · 날짜는 그대로 두고 품목만 넣어요.",
        "'품목별로 따로 저장'을 켜면 품목마다 내역이 따로 생겨요. 끄면 주문 1건으로 저장하고, 통계만 품목별 카테고리로 나눠요.",
        "같은 스크린샷을 다시 보내면 AI를 다시 부르지 않고 예전 분석을 열어요. 그 내역을 지웠다면 다시 저장할 수 있어요.",
    )),
    Help("카드 문자 · 알림 자동 기록", "결제 알림이 오면 알아서 적어요", listOf(
        "알림 접근을 허용하면 결제 알림만 골라 읽어요. 결제가 아닌 메시지는 저장하지 않아요.",
        "같은 결제가 문자와 앱 알림으로 두 번 와도 한 번만 적고, 취소 알림이 오면 반영해요.",
        "내 계좌끼리 옮긴 돈, 카드값, 페이머니 충전, 택시 가승인, 세이프박스 이동은 적지 않아요. 내 계좌끼리를 알아보도록 '내 이름'을 적어 두세요.",
        "확실하지 않은 건은 '확인 필요'로 들어가요. 홈에서 눌러 확인해요.",
        "특정 알림을 막으려면 '자동 기록 막기'에 단어를 넣어요.",
        "배터리 사용을 '제한 없음'으로 해 두면 앱이 잠든 사이에도 알림을 놓치지 않아요.",
        "해외 결제는 그날 환율로 원화로 바꿔 적어요.",
    )),
    Help("카테고리 · 태그", "카테고리는 하나, 태그는 여러 개", listOf(
        "내역마다 카테고리는 하나, 태그(#)는 여러 개 붙일 수 있어요. 예: 식비 #야식 #친구 · 모임.",
        "카테고리 아래 태그는 그 카테고리에만, 공통 태그(#데이트, #가족 …)는 어디에나 붙어요.",
        "설정 → 카테고리 편집에서 길게 눌러 끌면 순서를 바꾸고, 태그를 다른 카테고리나 공통 태그 아래로 옮길 수 있어요.",
        "자동으로 고르는 순서: 꾹 눌러 만든 규칙 → (직접 입력은) 그 가맹점의 지난 기록 → 앱에 든 가맹점 사전 → AI.",
    )),
    Help("즐겨찾기 · 반복 기록", "자주 쓰는 내역과 매달 나가는 돈", listOf(
        "즐겨찾기: 자주 쓰는 내역을 저장해 두고, 직접 입력의 '즐겨찾기'로 불러와요. 정렬 · 검색이 되고, 사용자 정의 순서에서는 길게 눌러 끌어 순서를 바꿔요.",
        "반복 기록: 월세 · 구독료처럼 매주 · 매월 정한 날 오전 9시에 알아서 기록해요. 앱을 못 연 날이 있어도 다음에 채워요.",
        "설정 → 즐겨찾기 · 반복 기록에서 추가 · 수정 · 삭제해요.",
    )),
    Help("월간 리포트", "한 달을 AI가 정리해요", listOf(
        "설정 → AI 월간 리포트를 켜면, 다음 달 첫날 오전 9시에 지난달 리포트를 만들어 알림으로 보내요.",
        "숫자는 앱이 직접 계산하고, AI는 그 숫자로 글만 써요. 내역 하나하나가 아니라 합계만 보내요.",
        "분석 탭의 '리포트'나 설정 → 지난 리포트 보기에서 다시 봐요.",
    )),
    Help("홈 화면 위젯", "앱을 안 열어도 이번 달이 보여요", listOf(
        "홈 화면에 Pocketlog 위젯을 놓으면 이번 달 쓴 돈과 예산 막대가 보여요.",
        "위젯을 크게 하면 오늘 쓴 돈, 많이 쓴 곳, 최근 내역까지 나오고 입력 · 촬영 · 사진 버튼이 생겨요.",
        "앱 잠금이 켜져 있으면 금액을 숨겨요.",
    )),
    Help("백업 · 복구", "휴대폰을 바꿔도 그대로", listOf(
        "백업 파일 만들기: 비밀번호로 암호화한 .plbak 파일로 저장해요.",
        "Google 드라이브에 백업: 내 드라이브의 Pocketlog 폴더에 올리고, 최근 5개만 남겨요.",
        "매일 자동 백업: 켜면 Google 계정에 연결하고 백업 비밀번호를 정해요. 그다음부터 하루 한 번 알아서 올려요.",
        "백업 비밀번호를 잊으면 그 백업은 열 수 없어요. 따로 적어 두세요.",
        "똑똑가계부 백업 파일에서 가져오기도 여기 있어요.",
    )),
    Help("앱 잠금 · AI 설정", "내 가계부를 지키고, AI를 고르고", listOf(
        "앱 잠금: 6자리 비밀번호나 지문 · 얼굴로 열어요. 다른 앱을 1분 넘게 쓰다 돌아오면 다시 물어봐요.",
        "AI 모델: 스크린샷 분석과 분류에 쓸 모델을 골라요. 똑똑할수록 비싸고 느려요.",
        "AI 사용 동의를 끄면 이미지와 문구를 보내지 않아요. 직접 입력과 카드 문자 자동 기록은 그대로 돼요.",
    )),
)

/** 설정 → 사용법: the whole app in a few lines, then each tab and sheet folded, opened by a tap. */
@Composable
fun HelpScreen(nav: Nav) {
    var open by rememberSaveable { mutableStateOf(-1) }
    PageScaffold("사용법", onBack = nav::pop) {
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                PCard(Modifier.padding(16.dp)) {
                    Text("Pocketlog는 이렇게 써요", style = MaterialTheme.typography.titleSmall)
                    overview.forEachIndexed { i, line -> Bullet("${i + 1}", line) }
                }
            }
            item { GroupLabel("화면별 자세히 · 눌러서 펼치기") }
            itemsIndexed(sections) { i, h ->
                Column(Modifier.fillMaxWidth().clickable { open = if (open == i) -1 else i }.padding(horizontal = 20.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(h.title, style = MaterialTheme.typography.bodyLarge)
                            Text(h.summary, style = MaterialTheme.typography.bodySmall, color = pal.sub)
                        }
                        Icon(if (open == i) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = pal.sub)
                    }
                    AnimatedVisibility(open == i) {
                        Column(Modifier.padding(top = 8.dp)) { h.lines.forEach { Bullet("•", it) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun Bullet(mark: String, text: String) {
    Row(Modifier.padding(top = 8.dp)) {
        Text(mark, style = MaterialTheme.typography.bodyMedium, color = pal.brand, modifier = Modifier.width(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
