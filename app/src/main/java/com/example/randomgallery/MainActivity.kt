package com.example.randomgallery

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.ScaleGestureDetector
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.randomgallery.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val imageList = mutableListOf<String>()
    private val random = Random()
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var scaleFactor = 1.0f
    private val minScale = 0.5f
    private val maxScale = 3.0f
    private var currentImagePath: String? = null

    private val deleteImageLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            currentImagePath?.let { path ->
                imageList.remove(path)
                Toast.makeText(this, "删除成功", Toast.LENGTH_SHORT).show()
                showRandomImage()
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            loadImages()
        } else {
            Toast.makeText(this, "需要相册权限来显示图片", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkPermissionAndLoadImages()

        // 初始化缩放检测器
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor *= detector.scaleFactor
                scaleFactor = scaleFactor.coerceIn(minScale, maxScale)
                binding.randomImageView.scaleX = scaleFactor
                binding.randomImageView.scaleY = scaleFactor
                return true
            }
        })

        // 记录触摸事件的开始时间
        var touchStartTime = 0L
        binding.root.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    touchStartTime = System.currentTimeMillis()
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val touchEndTime = System.currentTimeMillis()
                    val touchDuration = touchEndTime - touchStartTime
                    // 如果不是缩放操作，且触摸时间小于200毫秒，则认为是单击
                    if (!scaleGestureDetector.isInProgress && touchDuration < 200) {
                        showRandomImage()
                        // 重置缩放
                        scaleFactor = 1.0f
                        binding.randomImageView.scaleX = scaleFactor
                        binding.randomImageView.scaleY = scaleFactor
                    }
                }
            }
            scaleGestureDetector.onTouchEvent(event)
            true
        }

        binding.infoButton.setOnClickListener {
            currentImagePath?.let { path -> showImageInfo(path) }
        }

        binding.deleteButton.setOnClickListener {
            currentImagePath?.let { path -> deleteCurrentImage(path) }
        }
    }

    private fun deleteCurrentImage(imagePath: String) {
        val file = File(imagePath)
        if (!file.exists()) {
            Toast.makeText(this, "图片文件不存在", Toast.LENGTH_SHORT).show()
            return
        }

        // 检查写入媒体文件的权限
        val writePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        } else {
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, writePermission) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "需要写入权限来删除图片", Toast.LENGTH_SHORT).show()
            requestPermissionLauncher.launch(arrayOf(writePermission))
            return
        }

        AlertDialog.Builder(this)
            .setTitle("确认删除")
            .setMessage("确定要删除这张图片吗？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        val selection = MediaStore.Images.Media.DATA + "=?"
                        val selectionArgs = arrayOf(imagePath)

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            // Android 14及以上版本使用新的删除方法
                            val uris = mutableListOf<android.net.Uri>()
                            contentResolver.query(uri, arrayOf(MediaStore.Images.Media._ID), selection, selectionArgs, null)?.use { cursor ->
                                if (cursor.moveToFirst()) {
                                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                                    uris.add(android.content.ContentUris.withAppendedId(uri, id))
                                }
                            }
                            
                            if (uris.isNotEmpty()) {
                                val pendingIntent = MediaStore.createDeleteRequest(contentResolver, uris)
                                deleteImageLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@MainActivity, "请在系统界面确认删除", Toast.LENGTH_SHORT).show()
                                }
                                return@launch
                            }
                        }

                        // 对于Android 13及以下版本，使用原有的删除方法
                        val deletedRows = contentResolver.delete(uri, selection, selectionArgs)
                        withContext(Dispatchers.Main) {
                            if (deletedRows > 0) {
                                imageList.remove(imagePath)
                                Toast.makeText(this@MainActivity, "删除成功", Toast.LENGTH_SHORT).show()
                                showRandomImage()
                            } else {
                                Toast.makeText(this@MainActivity, "删除失败：文件可能已被移动或删除", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "删除失败：${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun checkPermissionAndLoadImages() {
        val readImagesPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        when {
            ContextCompat.checkSelfPermission(this, readImagesPermission) == PackageManager.PERMISSION_GRANTED -> {
                loadImages()
            }
            else -> {
                requestPermissionLauncher.launch(arrayOf(readImagesPermission))
            }
        }
    }

    private fun loadImages() {
        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "正在加载图片...", Toast.LENGTH_SHORT).show()
            }
            
            try {
                val projection = arrayOf(MediaStore.Images.Media.DATA)
                val cursor = contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    null
                )

                cursor?.use {
                    val columnIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                    while (it.moveToNext()) {
                        val imagePath = it.getString(columnIndex)
                        imageList.add(imagePath)
                    }
                }

                withContext(Dispatchers.Main) {
                    if (imageList.isNotEmpty()) {
                        showRandomImage()
                    } else {
                        Toast.makeText(this@MainActivity, "没有找到图片", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "加载图片时出错：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showRandomImage() {
        if (imageList.isEmpty()) return

        try {
            val randomIndex = random.nextInt(imageList.size)
            val imagePath = imageList[randomIndex]
            currentImagePath = imagePath
            
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val bitmap = BitmapFactory.decodeFile(imagePath)
                    withContext(Dispatchers.Main) {
                        binding.randomImageView.setImageBitmap(bitmap)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "加载图片失败：${e.message}", Toast.LENGTH_SHORT).show()
                        imageList.removeAt(randomIndex) // 移除无效的图片路径
                        currentImagePath = null
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this@MainActivity, "显示图片时出错：${e.message}", Toast.LENGTH_SHORT).show()
            currentImagePath = null
        }
    }

    private fun showImageInfo(imagePath: String) {
        val file = File(imagePath)
        if (!file.exists()) {
            Toast.makeText(this, "图片文件不存在", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(imagePath, options)

                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val lastModified = dateFormat.format(Date(file.lastModified()))
                val fileSize = String.format(Locale.getDefault(), "%.2f MB", file.length() / 1024.0 / 1024.0)

                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(getString(R.string.image_info_title))
                        .setMessage(
                            getString(R.string.image_info_name, file.name) + "\n\n" +
                            getString(R.string.image_info_size, options.outWidth, options.outHeight) + "\n\n" +
                            getString(R.string.image_info_file_size, fileSize) + "\n\n" +
                            getString(R.string.image_info_date, lastModified)
                        )
                        .setPositiveButton("确定", null)
                        .show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "获取图片信息失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}