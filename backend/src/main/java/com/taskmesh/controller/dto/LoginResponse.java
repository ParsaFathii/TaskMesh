package com.taskmesh.controller.dto;

/**
 * Successful login response (SPEC §8).
 *
 * @param token      signed JWT
 * @param tokenType  always "Bearer"
 * @param expiresIn  token lifetime in seconds (43200 = 12h)
 * @param user       authenticated identity
 */
public record LoginResponse(String token, String tokenType, long expiresIn, UserBrief user) {
}
