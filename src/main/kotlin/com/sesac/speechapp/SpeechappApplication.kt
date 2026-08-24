package com.sesac.speechapp

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class SpeechappApplication

fun main(args: Array<String>) {
	runApplication<SpeechappApplication>(*args)
}
