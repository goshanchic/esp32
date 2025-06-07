package com.example.esp32;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity3 extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main2);

        Button startButton = findViewById(R.id.startButton);
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity3.this, MainActivity4.class);
                startActivity(intent);
            }
        });

        Button dummyButton1 = findViewById(R.id.emptyButton1);
        Button dummyButton2 = findViewById(R.id.emptyButton2);

        dummyButton1.setOnClickListener(v -> {
            // Заглушка
        });

        dummyButton2.setOnClickListener(v -> {
            // Заглушка
        });
    }
}



