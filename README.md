# spelunker3

Scraping and data mining framework

## Chrome demo

```kotlin
fun main() = runBlocking {
    Chrome.create().use { chrome ->
        chrome.navigate("https://www.whatsmyip.org")
        println(chrome.evaluate("document.getElementById(\"ip\").textContent"))
    }
}
```

## SEC.gov

Scrape SEC form 4 filings (insiders) and sort by cost

```sh
$ just run sec-gov [--start-date=yyyy-mm-dd] [--end-date=yyyy-mm-dd] [--threshold=100000]
...
  NONE  384099800 https://www.sec.gov/Archives/edgar/data/1956484/0001193125-26-031422.txt
   WRB   33087353 https://www.sec.gov/Archives/edgar/data/11544/0001193125-26-032269.txt
   THM   25120703 https://www.sec.gov/Archives/edgar/data/1134115/0001013594-26-000107.txt
  NONE   24999998 https://www.sec.gov/Archives/edgar/data/1362558/0001193125-26-031011.txt
  HYMC    9198000 https://www.sec.gov/Archives/edgar/data/1925668/0001213900-26-010449.txt
   CRK    7947504 https://www.sec.gov/Archives/edgar/data/1232890/0001232890-26-000001.txt
  NONE  384099800 https://www.sec.gov/Archives/edgar/data/1956484/0001193125-26-031422.txt
   WRB   33087353 https://www.sec.gov/Archives/edgar/data/11544/0001193125-26-032269.txt
   THM   25120703 https://www.sec.gov/Archives/edgar/data/1134115/0001013594-26-000107.txt
  NONE   24999998 https://www.sec.gov/Archives/edgar/data/1362558/0001193125-26-031011.txt
  HYMC    9198000 https://www.sec.gov/Archives/edgar/data/1925668/0001213900-26-010449.txt
   CRK    7947504 https://www.sec.gov/Archives/edgar/data/1232890/0001232890-26-000001.txt
...
```
