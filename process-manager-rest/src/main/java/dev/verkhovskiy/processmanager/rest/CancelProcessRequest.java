package dev.verkhovskiy.processmanager.rest;

/** Запрос ручной отмены процесса. */
public record CancelProcessRequest(String reason) {}
