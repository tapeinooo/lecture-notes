# 縮小（R8）の設定
# 設定や記録は org.json で手で読み書きしているので、名前に頼る部分はほとんどない。
# 念のため、列挙型（設定に名前で保存している Mode・MicSource・Summary.Style）の名前は残す。
-keepclassmembers enum app.lecturenotes.** { *; }
# スタックトレースを読みやすく
-keepattributes SourceFile,LineNumberTable
