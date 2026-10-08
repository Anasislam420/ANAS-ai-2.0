package com.anas.ai


import android.app.Application
import com.anas.ai.data.preferences.AppPreferences
import com.anas.ai.data.repository.ChatRepository


class JarvisApp : Application() {


    lateinit var preferences: AppPreferences
        private set


    lateinit var chatRepository: ChatRepository
        private set


    override fun onCreate() {
        super.onCreate()
        instance = this
        preferences = AppPreferences(this)
        chatRepository = ChatRepository(preferences)
    }


    companion object {
        lateinit var instance: JarvisApp
            private set
    }
}
