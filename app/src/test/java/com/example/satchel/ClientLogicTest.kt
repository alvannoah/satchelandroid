package com.example.satchel

import com.example.satchel.data.ApiClient
import com.example.satchel.data.DocumentDetail
import com.example.satchel.data.DocumentPage
import com.example.satchel.data.Filters
import com.example.satchel.data.Hierarchy
import com.example.satchel.data.MyCourses
import com.example.satchel.data.Pkce
import com.example.satchel.data.TokenResponse
import com.example.satchel.ui.FileKind
import com.example.satchel.ui.formatSize
import com.example.satchel.ui.isAcademicYear
import com.example.satchel.ui.percent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceTest {
    @Test
    fun challengeMatchesRfc7636Example() {
        // RFC 7636, appendix B
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun verifierMeetsTheApiPattern() {
        // The API accepts ^[A-Za-z0-9_-]{43,128}$
        repeat(20) { assertTrue(Regex("^[A-Za-z0-9_-]{43,128}$").matches(Pkce.newVerifier())) }
        assertTrue(Regex("^[A-Za-z0-9_-]{43,128}$").matches(Pkce.challenge(Pkce.newVerifier())))
    }

    @Test
    fun verifiersAreRandom() {
        assertFalse(Pkce.newVerifier() == Pkce.newVerifier())
    }
}

/** The response shapes from SimilarityEngine/satchel/schemas.py decode into the app's models. */
class DecodingTest {
    private val json = ApiClient("https://example.test/api", token = { null }, onUnauthorized = {}).json

    private val document = """
        {"id": 7, "title": "Lecture 3: Pipelining", "course": {"id": 2, "code": "CS201", "name": "Computer Structures"},
         "document_type": {"id": 1, "name": "Lecture notes"}, "academic_year": "2025/2026", "official": true,
         "status": "published", "original_filename": "L03.pdf", "size_bytes": 482133,
         "uploaded_at": "2026-11-17T09:30:00", "uploader": {"id": 3, "full_name": "Dr Okello"},
         "reviewed_at": null, "review_note": null}
    """

    @Test
    fun tokenResponse() {
        val r = json.decodeFromString<TokenResponse>("""
            {"access_token": "abc", "token_type": "bearer", "expires_in": 28800,
             "user": {"id": 1, "email": "s@example.test", "full_name": "A Student", "role": "student",
                      "is_active": true, "created_at": "2026-11-01T10:00:00", "moderated_courses": []}}
        """)
        assertEquals("abc", r.accessToken)
        assertEquals("A Student", r.user.fullName)
    }

    @Test
    fun documentDetailWithRecommendations() {
        val d = json.decodeFromString<DocumentDetail>("""
            {"document": $document, "can_moderate": false, "duplicate_flags": [],
             "related": [{"score": 0.42, "document": $document}], "copies_hidden": 1}
        """)
        assertEquals("CS201", d.document.course.code)
        assertEquals("Lecture notes", d.document.documentType.name)
        assertEquals(0.42, d.related.single().score, 1e-9)
        assertNull(d.document.reviewNote)
    }

    @Test
    fun readingMarksAndWhetherTheServerHasThem() {
        // Older servers: no scope, no marks. The app then hides the Reading filter.
        val old = json.decodeFromString<DocumentPage>("""{"total": 1, "page": 1, "page_size": 25, "items": [$document]}""")
        assertNull(old.scope)
        assertNull(old.items.single().reading)

        val withMark = document.replace("\"review_note\": null", "\"review_note\": null, \"reading\": {\"percent\": 38, \"last_page\": 6, \"completed\": false}")
        val new = json.decodeFromString<DocumentPage>("""{"scope": "all", "total": 1, "page": 1, "page_size": 25, "items": [$withMark]}""")
        assertEquals("all", new.scope)
        assertEquals(38, new.items.single().reading?.percent)
        assertFalse(new.items.single().reading!!.completed)
    }

    @Test
    fun courseSetupFields() {
        // Older servers send neither field: no setup needed, and a password to change.
        val old = json.decodeFromString<TokenResponse>("""
            {"access_token": "t", "expires_in": 1, "user": {"id": 1, "email": "e", "full_name": "A B", "role": "student",
             "is_active": true, "created_at": "2026-11-01T10:00:00", "moderated_courses": []}}
        """).user
        assertFalse(old.needsCourseSetup)
        assertTrue(old.hasPassword)

        val fresh = json.decodeFromString<TokenResponse>("""
            {"access_token": "t", "expires_in": 1, "user": {"id": 1, "email": "e", "full_name": "A B", "role": "student",
             "is_active": true, "created_at": "2026-11-01T10:00:00", "moderated_courses": [], "year_of_study": null,
             "needs_course_setup": true, "has_password": false}}
        """).user
        assertTrue(fresh.needsCourseSetup)
        assertFalse(fresh.hasPassword)

        val mine = json.decodeFromString<MyCourses>("""
            {"chosen": true, "year_of_study": 1, "courses": [
              {"id": 2, "programme_id": 1, "code": "DSE-MYSQL", "name": "MySQL", "year_of_study": 1, "semester": 2}]}
        """)
        assertEquals(listOf("DSE-MYSQL"), mine.courses.map { it.code })
    }

    @Test
    fun pageCopiesAndProgress() {
        // Older servers send no "viewable": it means no page copy.
        assertFalse(json.decodeFromString<DocumentDetail>(
            """{"document": $document, "can_moderate": false, "duplicate_flags": [], "related": [], "copies_hidden": 0}""",
        ).document.viewable)

        val withCopy = document.replace("\"review_note\": null", "\"review_note\": null, \"viewable\": true, \"page_count\": 16")
        val d = json.decodeFromString<DocumentDetail>("""
            {"document": $withCopy, "can_moderate": false, "duplicate_flags": [], "related": [], "copies_hidden": 0,
             "progress": {"document_id": 7, "last_page": 4, "furthest_page": 6, "page_count": 16, "percent": 38,
                          "completed": false, "started_at": "2026-11-18T20:00:00", "updated_at": "2026-11-18T20:05:00",
                          "completed_at": null}}
        """)
        assertTrue(d.document.viewable)
        assertEquals(16, d.document.pageCount)
        assertEquals(4, d.progress?.lastPage)
        assertEquals(6, d.progress?.furthestPage)
    }

    @Test
    fun hierarchy() {
        val h = json.decodeFromString<Hierarchy>("""
            {"institution": "ISBAT", "mode": "institution",
             "programmes": [{"id": 1, "name": "BSc SE", "courses": [
                {"id": 2, "code": "CS201", "name": "Computer Structures", "year_of_study": 2, "semester": 1,
                 "published_documents": 12}]}],
             "document_types": [{"id": 1, "name": "Lecture notes"}], "academic_years": ["2025/2026"],
             "ai_quiz_available": true}
        """)
        assertEquals(12, h.courses.single().publishedDocuments)
        assertTrue(h.aiQuizAvailable)
    }
}

class FormatTest {
    @Test
    fun filtersBecomeTheApisQueryParameters() {
        assertEquals(
            listOf("course_id" to "2", "official" to "false", "academic_year" to "2025/2026", "reading" to "in_progress"),
            Filters(courseId = 2, official = false, academicYear = "2025/2026", reading = "in_progress").asQuery(),
        )
        assertTrue(Filters().asQuery().isEmpty())
    }

    @Test
    fun academicYearFollowsTheApisRule() {
        assertTrue(isAcademicYear("2025/2026"))
        assertFalse(isAcademicYear("2025/2027"))
        assertFalse(isAcademicYear("2025-2026"))
    }

    @Test
    fun fileKinds() {
        assertEquals(FileKind.Pdf, FileKind.of("Notes.PDF"))
        assertEquals(FileKind.Pptx, FileKind.of("slides.pptx"))
        assertEquals(FileKind.Other, FileKind.of("README"))
    }

    @Test
    fun sizesAndScores() {
        assertEquals("1 KB", formatSize(10))
        assertEquals("471 KB", formatSize(482133))
        assertEquals("57%", percent(0.57))
    }
}
