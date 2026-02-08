# Custom View Scene

The CXR-M SDK provides a customizable display interface, enabling developers to show custom content on the glasses without developing glasses-end pages.

## 1. Open the Custom Interface

Use `fun openCustomView(content: String): CxrStatus?` where `content` is the JSON page initialization content.

```kotlin
fun openCustomView(content: String): ValueUtil.CxrStatus {
    return CxrApi.getInstance().openCustomView(content)
}
```

## 2. Listen to Custom Page Status

```kotlin
private val customViewListener = object : CustomViewListener {
    override fun onIconsSent() { }
    override fun onOpened() { }
    override fun onOpenFailed(p0: Int) { }
    override fun onUpdated() { }
    override fun onClosed() { }
}

fun setCustomViewListener(set: Boolean){
    CxrApi.getInstance().setCustomViewListener(if (set) customViewListener else null)
}
```

## 3. Update the Page

```kotlin
fun updateCustomView(content: String): ValueUtil.CxrStatus {
    return CxrApi.getInstance().updateCustomView(content)
}
```

## 4. Close the Page

```kotlin
fun closeCustomView(): ValueUtil.CxrStatus {
    return CxrApi.getInstance().closeCustomView()
}
```

## 5. Upload Image Resources

Images must not exceed **128x128px**. Keep the number of images under 10 for performance.

```kotlin
/**
 * @param icons List of IconInfo (name + Base64 data)
 * Note: Only the GREEN channel of the image will be displayed on the glasses
 */
fun sendCustomIcons(icons: List<IconInfo>): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().sendCustomViewIcons(icons)
}
```

The `IconInfo` class contains:
- `name`: Used to identify the icon during initialization or updates
- `data`: Base64-encoded image data

> **Note:** Only the green channel of the image will be displayed on the glasses; other channels will not be displayed.

## 6. Initialize JSON Fields

The page description uses JSON. Supported layouts: **LinearLayout** and **RelativeLayout**.

### Layout Properties

| Component | Param | Value |
| --- | --- | --- |
| **LinearLayout** | id | [id] |
| | layout_width, layout_height | match_parent, wrap_content, [value]dp |
| | layout_gravity, gravity | start, top, end, bottom, center, center_horizontal, center_vertical |
| | orientation | vertical, horizontal |
| | marginStart/Top/End/Bottom | [value]dp |
| | layout_weight | [value] |
| | paddingStart/Top/End/Bottom | [value]dp |
| | backgroundColor | #FF000000 |
| **RelativeLayout** | id | [id] |
| | layout_width, layout_height | match_parent, wrap_content, [value]dp |
| | paddingStart/End/Top/Bottom | [value]dp |
| | backgroundColor | #FF000000 |
| | marginStart/End/Top/Bottom | [value]dp |
| | layout_toStartOf/above/toEndOf/below/alignBaseLine/alignStart/alignEnd/alignTop/alignBottom | [id] |
| | layout_alignParentStart/End/Top/Bottom, layout_centerInParent/Horizontal/Vertical | true, false |

### Component Properties

| Component | Param | Value |
| --- | --- | --- |
| **TextView** | id | [id] |
| | layout_width, layout_height | match_parent, wrap_content, [value]dp |
| | text | [text] |
| | textColor | #FF00FF00 |
| | textSize | [value]sp |
| | gravity | start, top, end, bottom, center, center_horizontal, center_vertical |
| | textStyle | bold, italic |
| | padding/margin | [value]dp |
| **ImageView** | id | [id] |
| | layout_width, layout_height | match_parent, wrap_content, [value]dp |
| | name | [icon_name] (references uploaded icon) |
| | scaleType | matrix, fix_xy, fix_start, fix_center, fix_end, center, center_crop, center_inside |

### Example: Initialization JSON

```json
{
  "type": "LinearLayout",
  "props": {
    "layout_width": "match_parent",
    "layout_height": "match_parent",
    "orientation": "vertical",
    "gravity": "center_horizontal",
    "paddingTop": "140dp",
    "paddingBottom": "100dp",
    "backgroundColor": "#FF000000"
  },
  "children": [
    {
      "type": "TextView",
      "props": {
        "id": "tv_title",
        "layout_width": "wrap_content",
        "layout_height": "wrap_content",
        "text": "Init Text",
        "textSize": "16sp",
        "textColor": "#FF00FF00",
        "textStyle": "bold",
        "marginBottom": "20dp"
      }
    },
    {
      "type": "RelativeLayout",
      "props": {
        "width": "match_parent",
        "height": "100dp",
        "backgroundColor": "#00000000",
        "padding": "10dp"
      },
      "children": [
        {
          "type": "ImageView",
          "props": {
            "id": "iv_icon",
            "layout_width": "60dp",
            "layout_height": "60dp",
            "name": "icon_name0",
            "layout_alignParentStart": "true",
            "layout_centerVertical": "true"
          }
        },
        {
          "type": "TextView",
          "props": {
            "id": "tv_text",
            "layout_width": "wrap_content",
            "layout_height": "wrap_content",
            "text": "Text to the end of Icon",
            "textSize": "16sp",
            "textColor": "#FF00FF00",
            "layout_toEndOf": "iv_icon",
            "layout_centerVertical": "true",
            "marginStart": "15dp"
          }
        }
      ]
    }
  ]
}
```

## 7. Update JSON Fields

Update content uses JSON format. Must include the `id` of the element to update.

```json
[
  {
    "action": "update",
    "id": "tv_title",
    "props": {
      "text": "Update Text"
    }
  },
  {
    "action": "update",
    "id": "iv_icon",
    "props": {
      "name": "icon_name1"
    }
  }
]
```
