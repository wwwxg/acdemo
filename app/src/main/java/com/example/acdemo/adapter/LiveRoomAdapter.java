package com.example.acdemo.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import com.example.acdemo.R;
import com.example.acdemo.model.LiveRoom;
import java.util.List;

public class LiveRoomAdapter extends ArrayAdapter<LiveRoom> {
    private Context context;
    private List<LiveRoom> liveRooms;

    public LiveRoomAdapter(Context context, List<LiveRoom> liveRooms) {
        super(context, 0, liveRooms);
        this.context = context;
        this.liveRooms = liveRooms;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_live_room, parent, false);
        }

        LiveRoom room = liveRooms.get(position);
        TextView nameText = convertView.findViewById(R.id.nameText);
        TextView medalInfo = convertView.findViewById(R.id.medalInfo);

        nameText.setText(room.getName());
        medalInfo.setText(room.getMedalInfo());

        return convertView;
    }
} 