$bom = [System.Text.Encoding]::UTF8.GetPreamble()
foreach ($f in @('news_articles.csv', 'news_similarity_group.csv', 'news_ai_analysis.csv')) {
    $path = "C:\project\NewsSignal_stock2\$f"
    if (Test-Path $path) {
        $content = [System.IO.File]::ReadAllBytes($path)
        $newContent = new-object byte[] ($bom.Length + $content.Length)
        [System.Array]::Copy($bom, $newContent, $bom.Length)
        [System.Array]::Copy($content, 0, $newContent, $bom.Length, $content.Length)
        [System.IO.File]::WriteAllBytes($path, $newContent)
    }
}
