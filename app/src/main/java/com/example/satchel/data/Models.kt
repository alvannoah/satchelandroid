package com.example.satchel.data

import kotlinx.serialization.Serializable

/*
 * The API's response bodies (SimilarityEngine/satchel/schemas.py). JSON keys are
 * snake_case; the Json instance in ApiClient maps them to these camelCase names.
 */

@Serializable
data class CourseRef(val id: Int, val code: String, val name: String)

@Serializable
data class User(
    val id: Int,
    val email: String,
    val fullName: String,
    val role: String,                 // student, lecturer, moderator or admin
    val isActive: Boolean = true,
    val moderatedCourses: List<CourseRef> = emptyList(),
)

@Serializable
data class TokenResponse(val accessToken: String, val expiresIn: Int, val user: User)

@Serializable
data class Provider(val id: String, val name: String)

@Serializable
data class DocumentType(val id: Int, val name: String)

@Serializable
data class PersonRef(val id: Int, val fullName: String)

object Status {
    const val PENDING = "pending"
    const val PUBLISHED = "published"
    const val REJECTED = "rejected"
}

@Serializable
data class Document(
    val id: Int,
    val title: String,
    val course: CourseRef,
    val documentType: DocumentType,
    val academicYear: String? = null,
    val official: Boolean,
    val status: String,
    val originalFilename: String,
    val sizeBytes: Long,
    val uploadedAt: String,
    val uploader: PersonRef? = null,
    val reviewedAt: String? = null,
    val reviewNote: String? = null,
)

// ------------------------------- browsing --------------------------------- //

@Serializable
data class CourseNode(
    val id: Int,
    val code: String,
    val name: String,
    val yearOfStudy: Int,
    val semester: Int,
    val publishedDocuments: Int,
)

@Serializable
data class ProgrammeNode(val id: Int, val name: String, val courses: List<CourseNode>)

@Serializable
data class Hierarchy(
    val institution: String? = null,
    val mode: String,
    val programmes: List<ProgrammeNode>,
    val documentTypes: List<DocumentType>,
    val academicYears: List<String>,
    val aiQuizAvailable: Boolean = false,
) {
    val courses: List<CourseNode> get() = programmes.flatMap { it.courses }
}

@Serializable
data class DocumentPage(val total: Int, val page: Int, val pageSize: Int, val items: List<Document>)

@Serializable
data class SearchHit(val rank: Int, val score: Double, val document: Document)

@Serializable
data class SearchResults(
    val query: String,
    val method: String,
    val matchedTerms: List<String>,
    val results: List<SearchHit>,
)

@Serializable
data class RelatedDocument(val score: Double, val document: Document)

@Serializable
data class DuplicateFlag(
    val matchedDocumentId: Int,
    val matchedTitle: String,
    val matchedStatus: String,
    val score: Double,
    val exactFileMatch: Boolean,
)

@Serializable
data class DocumentDetail(
    val document: Document,
    val canModerate: Boolean,
    val duplicateFlags: List<DuplicateFlag>,   // only for the uploader and moderators
    val related: List<RelatedDocument>,
    val copiesHidden: Int,
)

// -------------------------------- uploads --------------------------------- //

@Serializable
data class DuplicateMatch(
    val documentId: Int,
    val title: String,
    val score: Double,
    val label: String,            // DUPLICATE, RELATED or DIFFERENT
    val exactFileMatch: Boolean,
)

@Serializable
data class DuplicateCheck(
    val status: String,           // POTENTIAL DUPLICATE, RELATED DOCUMENTS FOUND or NO CLOSE MATCH
    val bestScore: Double,
    val matches: List<DuplicateMatch>,
)

@Serializable
data class UploadResponse(
    val document: Document,
    val published: Boolean,       // false: waiting for a moderator
    val duplicateCheck: DuplicateCheck,
)

// -------------------------------- quizzes --------------------------------- //

@Serializable
data class QuizQuestion(
    val number: Int,
    val prompt: String,           // the sentence with its key term blanked out as _____
    val options: List<String>,
    val answer: Int,              // index into options
    val sentence: String,         // the document's own words behind the answer
    val explanation: String? = null,
)

@Serializable
data class Quiz(
    val documentId: Int,
    val title: String,
    val method: String,           // "tfidf" or "ai"
    val notice: String,
    val questions: List<QuizQuestion>,
)

/** Filters shared by browsing and search; null means "any". */
data class Filters(
    val programmeId: Int? = null,
    val courseId: Int? = null,
    val yearOfStudy: Int? = null,
    val semester: Int? = null,
    val documentTypeId: Int? = null,
    val official: Boolean? = null,
    val academicYear: String? = null,
) {
    val isEmpty: Boolean get() = this == Filters()

    fun asQuery(): List<Pair<String, String>> = listOfNotNull(
        programmeId?.let { "programme_id" to "$it" },
        courseId?.let { "course_id" to "$it" },
        yearOfStudy?.let { "year_of_study" to "$it" },
        semester?.let { "semester" to "$it" },
        documentTypeId?.let { "document_type_id" to "$it" },
        official?.let { "official" to "$it" },
        academicYear?.let { "academic_year" to it },
    )
}
