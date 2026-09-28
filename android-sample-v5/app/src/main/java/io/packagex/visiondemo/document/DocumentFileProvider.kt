package io.packagex.visiondemo.document

import androidx.core.content.FileProvider

/** The app's own provider class, so its manifest entry can't merge with a library's `FileProvider` entry. */
class DocumentFileProvider : FileProvider()
