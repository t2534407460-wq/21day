package com.twentyone.rhythm

import org.json.JSONObject
import java.time.LocalDate

data class CoachDraft(val day:String,val bed:String?=null,val rise:String?=null,val energy:String?=null,val reason:String?=null,val note:String?=null) {
    fun changes(original:DayLog):List<Pair<String,String>> = buildList {
        bed?.let { add("上床" to "${original.bed.ifEmpty { "未记录" }} → $it") }
        rise?.let { add("起床" to "${original.rise.ifEmpty { "未记录" }} → $it") }
        energy?.let { add("早上精神" to "${original.energy.ifEmpty { "未记录" }} → $it") }
        reason?.let { add("影响原因" to "${original.reason.ifEmpty { "未记录" }} → $it") }
        note?.let { add("追加备注" to it) }
    }
    fun matches(original:DayLog,latest:DayLog)=
        (bed==null || original.bed==latest.bed) && (rise==null || original.rise==latest.rise) &&
        (energy==null || original.energy==latest.energy) && (reason==null || original.reason==latest.reason) &&
        (note==null || original.note==latest.note)
    fun merge(latest:DayLog)=latest.copy(bed=bed ?: latest.bed,rise=rise ?: latest.rise,energy=energy ?: latest.energy,
        reason=reason ?: latest.reason,note=if(note==null) latest.note else if(latest.note.isBlank()) note else latest.note+"\n补记："+note)
}
object CoachDrafts {
    data class Input(val day:LocalDate,val text:String)
    fun split(text:String,reference:LocalDate,today:LocalDate):List<Input> {
        require(!Regex("上周|下周|周[一二三四五六日天]|星期|上个月|下个月").containsMatchIn(text)) { "请选好参考日期，或把日期写成YYYY-MM-DD再补记" }
        val pattern=Regex("\\d{4}-\\d{2}-\\d{2}|今天|今早|今晚|昨天|昨晚|前天|明天|后天")
        val matches=pattern.findAll(text).toList()
        val pieces=mutableListOf<Input>()
        if(matches.isEmpty()) pieces.add(Input(reference,text))
        else {
            val prefix=text.substring(0,matches.first().range.first).trim(' ','，',',','。')
            matches.forEachIndexed { index,match ->
                val day=when(match.value) {
                    "今天","今早","今晚"->reference
                    "昨天","昨晚"->reference.minusDays(1)
                    "前天"->reference.minusDays(2)
                    "明天"->reference.plusDays(1)
                    "后天"->reference.plusDays(2)
                    else->LocalDate.parse(match.value)
                }
                val part=text.substring(match.range.last+1,matches.getOrNull(index+1)?.range?.first ?: text.length).trim(' ','，',',','。')
                require(part.isNotBlank()) { "日期后请补充对应的时间或感受" }
                if(match.value=="昨晚") require(!Regex("凌晨|早上|上午|(?<![0-9])[0-9](?:[:：点时])|[一二三四五六七八九十]点").containsMatchIn(part)) { "跨午夜的昨晚有日期歧义，请写明YYYY-MM-DD" }
                pieces.add(Input(day,if(index==0 && prefix.isNotBlank()) "$prefix$part" else part))
            }
        }
        val grouped=pieces.groupBy { it.day }.map { (day,items)->Input(day,items.joinToString("，"){it.text}) }
        require(grouped.size in 1..3 && grouped.all { it.day in today.minusDays(365)..today }) { "一次最多补记3个日期，且必须在最近一年内、不晚于今天" }
        return grouped
    }
    suspend fun extract(c:android.content.Context,text:String,reference:LocalDate,today:LocalDate):List<CoachDraft> =
        split(text,reference,today).flatMap { input ->
            val result=parse(CoachInference.generate(c,prompt(input.text,input.day),json=true),today,input.text)
            require(result.size==1 && result.single().day==input.day.toString()) { "模型未正确理解日期，请分开补记" }
            result
        }
    fun prompt(text:String,date:LocalDate):String = """
        Extract the explicitly stated daily routine facts from INPUT. Output ONLY a JSON object with a "changes" array.
        The app has already resolved the calendar date: ALL facts in INPUT belong to $date. Use this exact date.
        Each item has "day" (YYYY-MM-DD), plus ONLY fields stated in INPUT:
        "bed": HH:mm when explicitly going to bed (上床), not falling asleep;
        "rise": HH:mm getting out of bed (起床);
        "energy": 不错 / 一般 / 疲惫, only if a morning feeling was stated;
        "reason": 无 / 工作 / 手机 / 社交 / 没有困意 / 其他, only if an actual cause was stated;
        "note": exact original text for other sleep details.
        Omit unstated fields. Do not add questions, explanations, null fields, or guesses.
        Combine facts for the same date. Separate different dates. Include ALL stated facts.
        Example INPUT: 今天08:15起床，精神不错。
        Example OUTPUT: {"changes":[{"day":"$date","rise":"08:15","energy":"不错"}]}
        INPUT: ${JSONObject.quote(text)}
        OUTPUT:
    """.trimIndent()
    fun parse(answer:String,today:LocalDate,sourceText:String?=null):List<CoachDraft> {
        val cleaned=answer.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val root=JSONObject(cleaned)
        require(root.keys().asSequence().all { it in setOf("changes","question") }) { "模型返回了不支持的内容，请修改说法重试" }
        val question=root.optString("question").trim()
        require(question.isEmpty()) { question.take(200) }
        val list=root.getJSONArray("changes")
        require(list.length() in 1..3) { "没有识别到明确记录，请补充日期、时间或感受" }
        val drafts=(0 until list.length()).map { index ->
            val j=list.getJSONObject(index)
            require(j.keys().asSequence().all { it in setOf("day","bed","rise","energy","reason","note") }) { "模型返回了不支持的字段，未保存" }
            val day=j.getString("day"); val date=LocalDate.parse(day)
            require(date.toString()==day && date in today.minusDays(365)..today) { "仅支持补记最近一年且不晚于今天的日期，请核对" }
            fun field(key:String):String? = if(!j.has(key) || j.isNull(key)) null else (j.get(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("模型的 $key 字段无效，请重试")
            val bed=field("bed");val rise=field("rise");val energy=field("energy");val reason=field("reason");val note=field("note")
            require(listOfNotNull(bed,rise).all { Regex("[0-2][0-9]:[0-5][0-9]").matches(it) && parseTime(it)!=null }) { "识别的时间无效，请改用24小时制说明" }
            require(energy==null || energy in setOf("不错","一般","疲惫")) { "精神状态无法识别，请重试" }
            require(reason==null || reason in setOf("无","工作","手机","社交","没有困意","其他")) { "影响原因无法识别，请重试" }
            require(note==null || note.length<=300) { "补记文字过长，请缩短后重试" }
            if(sourceText!=null) {
                require(bed==null || Regex("上床|躺下|躺到床|躺在床").containsMatchIn(sourceText)) { "模型提取了原话未明确说明的上床时间。请写明上床时间，或只记录起床和感受。" }
                require(rise==null || Regex("起床|离床|起来|起了").containsMatchIn(sourceText)) { "请明确说明起床时间；醒来不一定代表起床。" }
                val energyWords=mapOf("不错" to "不错|很好|有精神|精神好|精力充沛", "一般" to "一般|还行|普通", "疲惫" to "疲惫|疲倦|累|困|没精神")
                require(energy==null || Regex(energyWords.getValue(energy)).containsMatchIn(sourceText)) { "原话中的精神感受不够明确，请补充不错、一般或疲惫。" }
                val reasonWords=mapOf("无" to "没有原因|无原因|没有影响|无", "工作" to "工作|加班", "手机" to "手机|刷视频|刷短视频", "社交" to "社交|聚会|聚餐|朋友", "没有困意" to "没有困意|不困|睡不着", "其他" to "其他")
                require(reason==null || Regex(reasonWords.getValue(reason)).containsMatchIn(sourceText)) { "模型提取了原话未明确提到的原因，请换一种说法重试。" }
                require(note==null || sourceText.contains(note)) { "模型改写了备注，请重试；备注只保留你的原话。" }
            }
            require(listOf(bed,rise,energy,reason,note).any { it!=null }) { "未提取到记录字段" }
            CoachDraft(day,bed,rise,energy,reason,note)
        }
        require(drafts.map { it.day }.distinct().size==drafts.size) { "日期重复，请分开补记" }
        return drafts
    }
}


