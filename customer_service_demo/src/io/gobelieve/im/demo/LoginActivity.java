package io.gobelieve.im.demo;

import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import com.beetle.bauhinia.PeerMessageActivity;
import com.beetle.bauhinia.api.IMHttpAPI;
import com.beetle.bauhinia.api.body.PostDeviceToken;
import com.beetle.im.IMService;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicHeader;
import org.apache.http.protocol.HTTP;
import org.json.JSONObject;

import java.io.InputStream;

import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Action1;

/**
 * LoginActivity
 * Description: 登录页面,给用户指定消息发送方Id
 */
public class LoginActivity extends BaseActivity implements View.OnClickListener {
    private EditText mEtAccount;

    AsyncTask mLoginTask;
    @Override
    protected void onBaseCreate(Bundle savedInstanceState) {
        setContentView(R.layout.activity_login);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        Button btnLogin = (Button) findViewById(R.id.btn_login);
        mEtAccount = (EditText) findViewById(R.id.et_username);
        btnLogin.setOnClickListener(this);
    }

    private void go2Chat(long sender, String token) {
        IMService.getInstance().setToken(token);

        IMHttpAPI.setToken(token);
        IMService.getInstance().setToken(token);
        IMService.getInstance().setUID(sender);
        IMService.getInstance().start();

        IMDemoApplication app = (IMDemoApplication)getApplication();
        //TODO 此处绑定推送的devicetoken

        Intent intent = new Intent(this, MessageListActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("current_uid", sender);
        startActivity(intent);
        finish();
    }



    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.btn_login) {
            if (mEtAccount.getText().toString().length() <= 0) {
                Toast.makeText(this, "请设置您的用户id", Toast.LENGTH_SHORT).show();
                return;
            }
            final long senderId = Long.parseLong(mEtAccount.getText().toString());
            if (senderId <= 0) {
                Toast.makeText(this, "用户id不能为0或者-1", Toast.LENGTH_SHORT).show();
                return;
            }

            if (mLoginTask != null) {
                return;
            }

            mLoginTask = new AsyncTask<Void, Integer, String>() {
                @Override
                protected String doInBackground(Void... urls) {
                    return LoginActivity.this.login(senderId);
                }
                @Override
                protected void onPostExecute(String result) {
                    mLoginTask = null;
                    if (result != null && result.length() > 0) {
                        //设置用户id,进入MainActivity
                        go2Chat(senderId, result);
                    } else {
                        Toast.makeText(LoginActivity.this, "登陆失败", Toast.LENGTH_SHORT).show();
                    }
                }
            }.execute();

        }
    }

    private String login(long uid) {
        //调用app自身的登陆接口获取im服务必须的access token,之后可将token保存在本地供下次直接登录IM服务
        //sandbox地址: "http://sandbox.demo.gobelieve.io"
        String URL = "http://demo.gobelieve.io";
        String uri = String.format("%s/auth/token", URL);
        try {
            HttpClient getClient = new DefaultHttpClient();
            HttpPost request = new HttpPost(uri);
            JSONObject json = new JSONObject();
            json.put("uid", uid);
            StringEntity s = new StringEntity(json.toString());
            s.setContentEncoding((Header) new BasicHeader(HTTP.CONTENT_TYPE, "application/json"));
            request.setEntity(s);

            HttpResponse response = getClient.execute(request);
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode != HttpStatus.SC_OK){
                System.out.println("login failure code is:"+statusCode);
                return null;
            }
            int len = (int)response.getEntity().getContentLength();
            byte[] buf = new byte[len];
            InputStream inStream = response.getEntity().getContent();
            int pos = 0;
            while (pos < len) {
                int n = inStream.read(buf, pos, len - pos);
                if (n == -1) {
                    break;
                }
                pos += n;
            }
            inStream.close();
            if (pos != len) {
                return null;
            }
            String txt = new String(buf, "UTF-8");
            JSONObject jsonObject = new JSONObject(txt);
            String accessToken = jsonObject.getString("token");
            return accessToken;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
