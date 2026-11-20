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
    val yearOfStudy: Int? = null,
    /** A student who has not yet said what they study: show the course setup first. */
    val needsCourseSetup: Boolean = false,
    /** False for accounts that only sign in with Google or another provider. */
    val hasPassword: Boolean = true,
) {
    val isStudent: Boolean get() = role == "student"
}

/** A course as /me/courses returns it. */
@Serializable
data class Course(
    val id: Int,
    val programmeId: Int,
    val code: String,
    val name: String,
    val yearOfStudy: Int,
    val semester: Int,
)

/** A student's own courses: the ones the library shows by default. */
@Serializable
data class MyCourses(
    val chosen: Boolean,          // false: never set up
    val yearOfStudy: Int? = null,
    val courses: List<Course>,
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
    /** A PDF copy exists for reading on screen (GET /documents/{id}/view). False from servers without it. */
    val viewable: Boolean = false,
    val pageCount: Int? = null,
    /** Your own reading state, in lists from newer servers; null if not started (or not sent). */
    val reading: ReadingMark? = null,
)

/** How far the signed-in person has read a document, as lists carry it. */
@Serializable
data class ReadingMark(val percent: Int, val lastPage: Int, val completed: Boolean)

/** How far the signed-in person has read a document. Pages start at 1. */
@Serializable
data class Progress(
    val documentId: Int,
    val lastPage: Int,        // where to resume
    val furthestPage: Int,    // only ever grows
    val pageCount: Int,
    val percent: Int,
    val completed: Boolean,
    val updatedAt: String? = null,
) {
    fun toMark() = ReadingMark(percent, lastPage, completed)
}

/** One entry in "continue reading". */
@Serializable
data class ReadingItem(val document: Document, val progress: Progress)

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
    /** Your own courses; null means every course (no choice made, or not a student). */
    val myCourseIds: List<Int>? = null,
) {
    val courses: List<CourseNode> get() = programmes.flatMap { it.courses }

    /** Whether the person has courses of their own, so "My courses / All courses" means something. */
    val hasOwnCourses: Boolean get() = myCourseIds != null

    fun isMine(courseId: Int): Boolean = myCourseIds?.contains(courseId) ?: false

    /** The person's own courses first, then the rest, keeping each group's order. */
    fun mineFirst(courses: List<CourseNode>): List<CourseNode> = courses.sortedBy { if (isMine(it.id)) 0 else 1 }
}

@Serializable
data class DocumentPage(
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val items: List<Document>,
    /** "mine" or "all". Absent from servers that predate courses per student and reading filters. */
    val scope: String? = null,
)

@Serializable
data class SearchHit(val rank: Int, val score: Double, val document: Document)

@Serializable
data class SearchResults(
    val query: String,
    val method: String,
    val matchedTerms: List<String>,
    val results: List<SearchHit>,
    val scope: String? = null,   // absent from older servers, as on DocumentPage
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
    val progress: Progress? = null,          // your own, once you have opened it
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
    /** "not_started", "in_progress" or "completed": your own reading state. */
    val reading: String? = null,
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
        reading?.let { "reading" to it },
    )
}
