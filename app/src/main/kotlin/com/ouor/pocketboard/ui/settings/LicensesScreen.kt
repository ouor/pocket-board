package com.ouor.pocketboard.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.ouor.pocketboard.ui.components.BackButton

/** 오픈소스 라이선스(SC12 정보). 목록은 빌드 때 AboutLibraries 플러그인이 의존성에서 만든다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val libraries by produceLibraries()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("오픈소스 라이선스", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad -> LibrariesContainer(libraries, Modifier.fillMaxSize().padding(pad)) }
}
