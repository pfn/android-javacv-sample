libraryDependencies += "com.google.guava" % "guava" % "19.0"

javacOptions in Compile ++= "-source" :: "1.7" :: "-target" :: "1.7" :: Nil

javaCppPresetLibs ++= Seq("opencv" -> "3.0.0", "ffmpeg" -> "2.8.1")
javaCppPlatform := Seq("android-arm")

minSdkVersion := "21"
targetSdkVersion := "21"

android.dsl.apkExclude(
  "META-INF/maven/org.bytedeco.javacpp-presets/opencv/pom.properties",
  "META-INF/maven/org.bytedeco.javacpp-presets/opencv/pom.xml",
  "META-INF/maven/org.bytedeco.javacpp-presets/videoinput/pom.properties",
  "META-INF/maven/org.bytedeco.javacpp-presets/videoinput/pom.xml",
  "META-INF/maven/org.bytedeco.javacpp-presets/ffmpeg/pom.properties",
  "META-INF/maven/org.bytedeco.javacpp-presets/ffmpeg/pom.xml"
)

useProguardInDebug := false
protifySettings
