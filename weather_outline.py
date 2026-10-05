"""Antialiased rendering for the desktop's custom frame canvases."""
import tkinter as tk
from PIL import Image, ImageDraw, ImageTk, ImageFont, ImageChops


def smooth_outlines(app):
    for canvas in app.winfo_children():
        if not isinstance(canvas,tk.Canvas):
            continue
        scale=4
        width=int(float(canvas.cget('width')))
        height=int(float(canvas.cget('height')))
        bitmap=Image.new('RGBA',(width*scale,height*scale))
        draw=ImageDraw.Draw(bitmap)
        silhouette=Image.new('L',bitmap.size,0)
        mask_draw=ImageDraw.Draw(silhouette)
        first_polygon=True
        for item in canvas.find_all():
            kind=canvas.type(item)
            coords=[value*scale for value in canvas.coords(item)]
            fill=canvas.itemcget(item,'fill') if kind in ('polygon','line','oval','text') else ''
            if kind=='polygon':
                points=list(zip(coords[::2],coords[1::2]))
                if first_polygon:
                    mask_draw.polygon(points,fill=255)
                    mask_draw.line(points+[points[0]],fill=255,width=scale,joint='curve')
                    first_polygon=False
                elif fill and min(canvas.coords(item)[1::2],default=0)>=269:
                    # The detached time bar belongs to the exterior mask too.
                    mask_draw.polygon(points,fill=255)
                    mask_draw.line(points+[points[0]],fill=255,width=scale,joint='curve')
                if fill:
                    draw.polygon(points,fill=fill)
                outline=canvas.itemcget(item,'outline')
                if outline:
                    draw.line(points+[points[0]],fill=outline,width=scale,joint='curve')
            elif kind=='line' and fill:
                draw.line(list(zip(coords[::2],coords[1::2])),fill=fill,width=scale,joint='curve')
            elif kind=='oval':
                draw.ellipse(coords,fill=fill or None,outline=canvas.itemcget(item,'outline') or None,width=scale)
            elif kind=='text':
                font=ImageFont.truetype('C:/Windows/Fonts/segoeui.ttf',10*scale)
                draw.text((coords[0],coords[1]),canvas.itemcget(item,'text'),font=font,fill=fill,anchor='mm')
        bitmap.putalpha(ImageChops.multiply(bitmap.getchannel('A'),silhouette))
        bitmap=bitmap.resize((width,height),Image.Resampling.LANCZOS)
        # Windows color-key transparency requires a binary exterior mask.
        # Interior border-to-fill transitions retain their antialiasing.
        alpha=bitmap.getchannel('A').point(lambda value:255 if value>=128 else 0)
        bitmap.putalpha(alpha)
        canvas.delete('all')
        canvas._smooth_frame=ImageTk.PhotoImage(bitmap)
        canvas.create_image(0,0,image=canvas._smooth_frame,anchor='nw')
