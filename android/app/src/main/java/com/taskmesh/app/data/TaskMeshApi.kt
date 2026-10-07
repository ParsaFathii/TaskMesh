package com.taskmesh.app.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit description of the TaskMesh REST API (SPEC §8). All paths are
 * relative to the configured base URL, which always ends in '/'.
 *
 *  - Errors surface as `retrofit2.HttpException` (non-2xx) or `java.io.IOException`
 *    (transport); [ApiErrors] turns them into user-facing messages, and
 *    [AuthInterceptor] handles 401s globally.
 *  - List endpoints return the SPEC §8 pagination envelope [PageDto]; logs,
 *    attempts and job-types return plain JSON arrays.
 */
interface TaskMeshApi {

    // -- auth ---------------------------------------------------------------

    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginRequestDto): LoginResponseDto

    @GET("api/v1/me")
    suspend fun me(): UserDto

    // -- projects ------------------------------------------------------------

    @GET("api/v1/projects")
    suspend fun projects(
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): PageDto<ProjectDto>

    @POST("api/v1/projects")
    suspend fun createProject(@Body body: ProjectCreateRequest): ProjectDto

    // -- jobs ----------------------------------------------------------------

    @GET("api/v1/jobs")
    suspend fun jobs(
        @Query("projectId") projectId: String?,
        @Query("status") status: String?,
        @Query("type") type: String?,
        @Query("priority") priority: String?,
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): PageDto<JobDto>

    @POST("api/v1/jobs")
    suspend fun createJob(@Body body: JobCreateRequest): JobDto

    /** GET /api/v1/jobs/{id} → {job, worker}. */
    @GET("api/v1/jobs/{id}")
    suspend fun jobDetail(@Path("id") id: String): JobDetailDto

    @POST("api/v1/jobs/{id}/cancel")
    suspend fun cancelJob(@Path("id") id: String): JobDto

    @POST("api/v1/jobs/{id}/retry")
    suspend fun retryJob(@Path("id") id: String): JobDto

    @GET("api/v1/jobs/{id}/logs")
    suspend fun jobLogs(
        @Path("id") id: String,
        @Query("level") level: String?,
        @Query("limit") limit: Int?,
    ): List<JobLogDto>

    @GET("api/v1/jobs/{id}/attempts")
    suspend fun attempts(@Path("id") id: String): List<JobAttemptDto>

    // -- workers & catalog ----------------------------------------------------

    @GET("api/v1/workers")
    suspend fun workers(
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): PageDto<WorkerDto>

    @GET("api/v1/job-types")
    suspend fun jobTypes(): List<JobTypeDto>
}
