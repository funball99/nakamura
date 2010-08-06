#!/usr/bin/env ruby

require 'sling/test'
include SlingUsers

class TC_Kern929Test < Test::Unit::TestCase
  include SlingTest

  def test_import_content_from_json_to_profile
    m = "1"+Time.now.to_i.to_s
    userid = "testuser-#{m}"
    hobby = "#{m}-Card Draw Poker"
    lastname = "Doe"
    importprop = "sakai:authprofile_import"
    profilecontent = "{\"basic\": {\"elements\": {\"hobby\":\"#{hobby}\"}}}"
    password = "testuser"
    @s.switch_user(User.admin_user())
    res = @s.execute_post(@s.url_for("#{$USER_URI}"), {
      ":name" => userid,
      "pwd" => password,
      "pwdConfirm" => password,
      importprop => profilecontent,
      "_charset_" => "UTF-8"
    })
    assert_equal("200", res.code, "Should have created user as admin")
    testuser = User.new(userid)
    public = testuser.public_path_for(@s)

    res = @s.execute_get(@s.url_for("#{public}/authprofile.tidy.3.json"))
    puts res.code, res.body

    res = @s.execute_get(@s.url_for("#{public}/authprofile.json"))
    puts res.code, res.body
    json = JSON.parse(res.body)
    assert_nil(json[importprop], "Import directive should not be stored as authprofile property")

    res = @s.execute_get(@s.url_for("#{public}/authprofile/basic/elements.json"))
    assert_equal("200", res.code, "Should have imported authprofile contents in postprocessing")
    json = JSON.parse(res.body)
    assert_equal(json["hobby"], hobby, "Profile contents were not imported correctly")

    userpath = User.url_for(userid)
    res = @s.execute_get(@s.url_for("#{userpath}.json"))
    puts res.code, res.body
    json = JSON.parse(res.body)
    assert_nil(json[importprop], "Import directive should not be stored as user property")

  end

end
