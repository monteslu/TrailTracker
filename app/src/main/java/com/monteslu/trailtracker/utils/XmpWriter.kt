package com.monteslu.trailtracker.utils

import com.monteslu.trailtracker.data.GpsPoint
import com.monteslu.trailtracker.managers.WeatherData
import android.os.Build
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.min

object XmpWriter {
    private const val XMP_HEADER = "http://ns.adobe.com/xap/1.0/\u0000"
    private const val APP1_MARKER = 0xFFE1
    
    fun addXmpToJpeg(jpegFile: File, gpsPoint: GpsPoint?, compass: Float, timestamp: Long, weather: WeatherData? = null) {
        try {
            // Read original JPEG
            val originalBytes = jpegFile.readBytes()
            
            // Create XMP packet
            val xmpPacket = createXmpPacket(gpsPoint, compass, timestamp, weather)
            
            // Write new JPEG with XMP
            jpegFile.writeBytes(insertXmpIntoJpeg(originalBytes, xmpPacket))
        } catch (e: Exception) {
            // Silently fail to not disrupt capture
        }
    }
    
    private fun createXmpPacket(gpsPoint: GpsPoint?, compass: Float, timestamp: Long, weather: WeatherData?): ByteArray {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")
        val isoDate = dateFormat.format(Date(timestamp))
        
        val xmp = buildString {
            append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
            append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n")
            append("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
            append("<rdf:Description rdf:about=\"\"\n")
            append("    xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\"\n")
            append("    xmlns:exif=\"http://ns.adobe.com/exif/1.0/\"\n")
            append("    xmlns:tiff=\"http://ns.adobe.com/tiff/1.0/\"\n")
            append("    xmlns:photoshop=\"http://ns.adobe.com/photoshop/1.0/\"\n")
            append("    xmlns:tt=\"http://ns.trailtracker/1.0/\">\n")
            
            // Standard XMP fields
            append("  <xmp:CreateDate>$isoDate</xmp:CreateDate>\n")
            append("  <xmp:CreatorTool>TrailTracker</xmp:CreatorTool>\n")
            append("  <xmp:ModifyDate>$isoDate</xmp:ModifyDate>\n")
            append("  <xmp:MetadataDate>$isoDate</xmp:MetadataDate>\n")
            
            // Standard TIFF fields for device info
            append("  <tiff:Make>${Build.MANUFACTURER}</tiff:Make>\n")
            append("  <tiff:Model>${Build.MODEL}</tiff:Model>\n")
            append("  <tiff:Software>TrailTracker Android</tiff:Software>\n")
            
            // Image dimensions (assuming 1920x1080 after crop)
            append("  <tiff:ImageWidth>1920</tiff:ImageWidth>\n")
            append("  <tiff:ImageLength>1080</tiff:ImageLength>\n")
            append("  <exif:PixelXDimension>1920</exif:PixelXDimension>\n")
            append("  <exif:PixelYDimension>1080</exif:PixelYDimension>\n")
            
            // Standard EXIF namespace GPS fields in XMP
            gpsPoint?.let { gps ->
                append("  <exif:GPSLatitude>${gps.lat}</exif:GPSLatitude>\n")
                append("  <exif:GPSLongitude>${gps.lon}</exif:GPSLongitude>\n")
                append("  <exif:GPSAltitude>${gps.alt}</exif:GPSAltitude>\n")
                append("  <exif:GPSAltitudeRef>${if (gps.alt >= 0) 0 else 1}</exif:GPSAltitudeRef>\n")
                append("  <exif:GPSSpeed>${gps.speed}</exif:GPSSpeed>\n")
                append("  <exif:GPSSpeedRef>M</exif:GPSSpeedRef>\n")
                append("  <exif:GPSImgDirection>$compass</exif:GPSImgDirection>\n")
                append("  <exif:GPSImgDirectionRef>M</exif:GPSImgDirectionRef>\n")
                append("  <exif:GPSTimeStamp>$isoDate</exif:GPSTimeStamp>\n")
                
                // GPS DOP (dilution of precision) from accuracy
                val hdop = gps.accuracy / 5.0 // Rough approximation
                append("  <exif:GPSDOP>${String.format("%.2f", hdop)}</exif:GPSDOP>\n")
            }
            
            // Custom TrailTracker namespace with full precision data
            append("  <tt:TimestampMs>$timestamp</tt:TimestampMs>\n")
            append("  <tt:Compass>$compass</tt:Compass>\n")
            
            gpsPoint?.let { gps ->
                append("  <tt:Latitude>${gps.lat}</tt:Latitude>\n")
                append("  <tt:Longitude>${gps.lon}</tt:Longitude>\n")
                append("  <tt:Altitude>${gps.alt}</tt:Altitude>\n")
                append("  <tt:Speed>${gps.speed}</tt:Speed>\n")
                append("  <tt:Accuracy>${gps.accuracy}</tt:Accuracy>\n")
                append("  <tt:GPSCompass>${gps.compass}</tt:GPSCompass>\n")
                append("  <tt:GPSTimestamp>${gps.timestamp}</tt:GPSTimestamp>\n")
            }
            
            // Weather data with "Weather" prefix to avoid collisions
            // IMPORTANT: WeatherTimeUnix is when the weather measurement was taken (could be hours/days old)
            // WeatherFetchedAtUnix is when we actually retrieved this data from the API
            weather?.let { w ->
                append("  <tt:WeatherTemperature>${w.temperature}</tt:WeatherTemperature>\n")
                append("  <tt:WeatherTemperatureUnit>C</tt:WeatherTemperatureUnit>\n")
                append("  <tt:WeatherWindSpeed>${w.windSpeed}</tt:WeatherWindSpeed>\n")
                append("  <tt:WeatherWindSpeedUnit>kmh</tt:WeatherWindSpeedUnit>\n")
                append("  <tt:WeatherWindDirection>${w.windDirection}</tt:WeatherWindDirection>\n")
                append("  <tt:WeatherCode>${w.weatherCode}</tt:WeatherCode>\n")
                append("  <tt:WeatherIsDay>${w.isDay}</tt:WeatherIsDay>\n")
                append("  <tt:WeatherInterval>${w.interval}</tt:WeatherInterval>\n")
                append("  <tt:WeatherTime>${w.time}</tt:WeatherTime>\n")
                append("  <tt:WeatherTimeUnix>${w.timeUnix}</tt:WeatherTimeUnix>\n")
                append("  <tt:WeatherFetchedAtUnix>${w.fetchedAt / 1000}</tt:WeatherFetchedAtUnix>\n")
                append("  <tt:WeatherLatitude>${w.latitude}</tt:WeatherLatitude>\n")
                append("  <tt:WeatherLongitude>${w.longitude}</tt:WeatherLongitude>\n")
                
                // Optional fields from API response
                w.elevation?.let { elev ->
                    append("  <tt:WeatherElevation>${elev}</tt:WeatherElevation>\n")
                }
                w.generationTimeMs?.let { genTime ->
                    append("  <tt:WeatherGenerationTimeMs>${genTime}</tt:WeatherGenerationTimeMs>\n")
                }
                w.utcOffsetSeconds?.let { offset ->
                    append("  <tt:WeatherUtcOffsetSeconds>${offset}</tt:WeatherUtcOffsetSeconds>\n")
                }
                w.timezone?.let { tz ->
                    append("  <tt:WeatherTimezone>${tz}</tt:WeatherTimezone>\n")
                }
                w.timezoneAbbreviation?.let { tzAbbr ->
                    append("  <tt:WeatherTimezoneAbbreviation>${tzAbbr}</tt:WeatherTimezoneAbbreviation>\n")
                }
            }
            
            append("</rdf:Description>\n")
            append("</rdf:RDF>\n")
            append("</x:xmpmeta>\n")
            
            // XMP padding for future edits
            repeat(2000) { append(' ') }
            append("\n")
            
            append("<?xpacket end=\"w\"?>")
        }
        
        return xmp.toByteArray()
    }
    
    private fun insertXmpIntoJpeg(jpegBytes: ByteArray, xmpPacket: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(jpegBytes.size + xmpPacket.size + 1024)
        val input = ByteArrayInputStream(jpegBytes)
        
        // Copy SOI marker
        output.write(input.read())
        output.write(input.read())
        
        // Insert XMP as APP1 segment after SOI
        val xmpData = ByteArrayOutputStream()
        xmpData.write(XMP_HEADER.toByteArray())
        xmpData.write(xmpPacket)
        
        val xmpBytes = xmpData.toByteArray()
        val segmentSize = xmpBytes.size + 2 // +2 for size bytes
        
        // Write APP1 marker
        output.write(0xFF)
        output.write(APP1_MARKER and 0xFF)
        
        // Write segment size (big endian)
        output.write((segmentSize shr 8) and 0xFF)
        output.write(segmentSize and 0xFF)
        
        // Write XMP data
        output.write(xmpBytes)
        
        // Copy rest of JPEG
        input.copyTo(output)
        
        return output.toByteArray()
    }
}