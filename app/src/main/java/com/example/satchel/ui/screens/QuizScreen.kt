package com.example.satchel.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.satchel.HierarchyCache
import com.example.satchel.data.ApiClient
import com.example.satchel.data.ApiException
import com.example.satchel.data.Quiz
import com.example.satchel.data.QuizQuestion
import com.example.satchel.ui.EmptyState
import com.example.satchel.ui.Loading
import com.example.satchel.ui.Notice
import com.example.satchel.ui.screenViewModel
import com.example.satchel.ui.theme.Satchel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val COUNTS = mapOf("tfidf" to listOf(5, 10, 20), "ai" to listOf(5, 10))   // AI quizzes keep 10 per document
private const val LETTERS = "ABCDEFGH"

class QuizViewModel(private val id: Int, private val api: ApiClient, private val cache: HierarchyCache) : ViewModel() {
    var aiAvailable by mutableStateOf(false)
        private set
    var method by mutableStateOf("tfidf")
        private set
    var count by mutableStateOf(10)
        private set
    var quiz by mutableStateOf<Quiz?>(null)
        private set
    var error by mutableStateOf<ApiException?>(null)
        private set
    var loading by mutableStateOf(true)
        private set

    /** Chosen option per question number. */
    val answers = mutableStateMapOf<Int, Int>()
    var current by mutableStateOf(0)

    private var job: Job? = null

    init {
        viewModelScope.launch {
            aiAvailable = runCatching { cache.get().aiQuizAvailable }.getOrDefault(false)
            load()
        }
    }

    /** A failed AI request is retried by the person, not automatically: each one costs money. */
    fun load() {
        job?.cancel()
        loading = true
        error = null
        quiz = null
        restart()
        job = viewModelScope.launch {
            try {
                quiz = api.quiz(id, count, method)
            } catch (e: ApiException) {
                error = e
            } finally {
                loading = false
            }
        }
    }

    fun choose(newMethod: String = method, newCount: Int = count) {
        val counts = COUNTS.getValue(newMethod)
        val c = if (newCount in counts) newCount else 10
        if (newMethod == method && c == count && quiz != null) return
        method = newMethod
        count = c
        load()
    }

    fun answer(question: QuizQuestion, option: Int) {
        if (question.number !in answers) answers[question.number] = option
    }

    fun restart() {
        answers.clear()
        current = 0
    }
}

@Composable
fun QuizScreen(documentId: Int, onBackToDocument: () -> Unit) {
    val vm = screenViewModel { QuizViewModel(documentId, it.api, it.hierarchy) }
    val quiz = vm.quiz
    val error = vm.error

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Practice quiz", style = MaterialTheme.typography.headlineSmall)
        Text(quiz?.title ?: " ", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Controls(vm)
        Spacer(Modifier.height(16.dp))

        when {
            vm.loading -> Box(Modifier.fillMaxWidth().height(320.dp)) {
                Loading(
                    message = if (vm.method == "ai") {
                        "An AI model is writing questions from this document…\nThe first time can take up to a minute. After that they load instantly."
                    } else "Preparing your questions…",
                )
            }
            error?.status == 409 -> Notice("Practice quizzes are available once a document is published.")
            error?.status == 404 && vm.method == "tfidf" -> Notice("This document doesn’t exist or isn’t available to you.", kind = "error")
            error != null -> {
                Notice(error.message ?: "Something went wrong.", kind = "error")
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::load) { Text("Try again") }
                    if (vm.method == "ai") OutlinedButton(onClick = { vm.choose(newMethod = "tfidf") }) { Text("Use key-term questions") }
                }
            }
            quiz == null -> {}
            quiz.questions.isEmpty() -> Box(Modifier.height(240.dp)) {
                EmptyState("This document doesn’t have enough clear text to make questions from.")
            }
            vm.current >= quiz.questions.size -> Results(quiz, vm, onBackToDocument)
            else -> QuestionCard(quiz, vm)
        }

        quiz?.let {
            Spacer(Modifier.height(16.dp))
            Text(it.notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Controls(vm: QuizViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (vm.aiAvailable) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("tfidf" to "Key terms", "ai" to "AI-written").forEachIndexed { i, (m, label) ->
                    SegmentedButton(
                        selected = vm.method == m,
                        onClick = { vm.choose(newMethod = m) },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                        enabled = !vm.loading,
                    ) { Text(label) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            COUNTS.getValue(vm.method).forEach { n ->
                FilterChip(selected = vm.count == n, onClick = { vm.choose(newCount = n) }, enabled = !vm.loading,
                    label = { Text("$n questions") })
            }
        }
    }
}

@Composable
private fun QuestionCard(quiz: Quiz, vm: QuizViewModel) {
    val questions = quiz.questions
    val q = questions[vm.current]
    val chosen = vm.answers[q.number]
    val colors = Satchel.colors

    LinearProgressIndicator(
        progress = { vm.answers.size / questions.size.toFloat() },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Question ${vm.current + 1} of ${questions.size}", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (quiz.method == "ai") "AI-written" else "Fill in the blank", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(12.dp))
    Text(promptText(q.prompt), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(16.dp))

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        q.options.forEachIndexed { i, option ->
            val answered = chosen != null
            val (border, background) = when {
                !answered -> MaterialTheme.colorScheme.outlineVariant to Color.Transparent
                i == q.answer -> colors.ok to colors.okBg
                i == chosen -> MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.outlineVariant to Color.Transparent
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, border),
                color = background,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = chosen == i, enabled = !answered, role = Role.RadioButton,
                        onClick = { vm.answer(q, i) }),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${LETTERS.getOrElse(i) { '?' }}", fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(24.dp))
                    Text(option, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    if (answered && i == q.answer) Icon(Icons.Filled.Check, contentDescription = "Correct", tint = colors.ok)
                    if (answered && i == chosen && i != q.answer) {
                        Icon(Icons.Filled.Close, contentDescription = "Your answer", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (chosen != null) {
        Spacer(Modifier.height(12.dp))
        val right = chosen == q.answer
        Notice(
            buildString {
                append(if (right) "Correct." else "The answer is “${q.options[q.answer]}”.")
                q.explanation?.let { append("\n\n").append(it) }
                append("\n\nFrom the document: “${q.sentence}”")
            },
            kind = if (right) "ok" else "error",
        )
    }

    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        OutlinedButton(onClick = { vm.current -= 1 }, enabled = vm.current > 0) { Text("Previous") }
        Button(onClick = { vm.current += 1 }, enabled = chosen != null) {
            Text(if (vm.current == questions.size - 1) "See results" else "Next")
        }
    }
}

@Composable
private fun Results(quiz: Quiz, vm: QuizViewModel, onBackToDocument: () -> Unit) {
    val questions = quiz.questions
    val score = questions.count { vm.answers[it.number] == it.answer }
    val colors = Satchel.colors

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { score / questions.size.toFloat() },
                modifier = Modifier.size(120.dp), strokeWidth = 10.dp,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Text("$score/${questions.size}", style = MaterialTheme.typography.headlineMedium)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            when {
                score == questions.size -> "Perfect score!"
                score >= questions.size * 0.7 -> "Well done."
                else -> "Keep practising."
            },
            style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
        )
    }
    Spacer(Modifier.height(16.dp))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        questions.forEach { q ->
            val chosen = vm.answers[q.number]
            val right = chosen == q.answer
            Row(verticalAlignment = Alignment.Top) {
                Icon(if (right) Icons.Filled.Check else Icons.Filled.Close, contentDescription = if (right) "Right" else "Missed",
                    tint = if (right) colors.ok else MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(q.options[q.answer]) }
                        if (!right && chosen != null) append(" · you chose “${q.options[chosen]}”")
                    }, style = MaterialTheme.typography.bodyMedium)
                    Text(q.sentence, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        Button(onClick = vm::restart) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Try again")
        }
        OutlinedButton(onClick = onBackToDocument) { Text("Back to the document") }
    }
}

/** Shows the blank (_____) as an underlined gap rather than underscores. */
private fun promptText(prompt: String) = buildAnnotatedString {
    val parts = prompt.split(Regex("_{3,}"))
    parts.forEachIndexed { i, part ->
        append(part)
        if (i < parts.lastIndex) {
            withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(" ".repeat(8)) }
        }
    }
}
